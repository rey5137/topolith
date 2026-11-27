package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.api.BoundaryContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ClientHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ThreadTenant;
import io.github.rey5137.topolith.spring.boot.errorfixture.payment.api.PaymentContract;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.service.invoker.HttpExchangeAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M6.3: MDC and tenant context across loopback and external calls (R-EXT-12, D-90, D-91). */
@ExtendWith(OutputCaptureExtension.class)
class ContextPropagationTest {
    private static final String KEYS = "topolith.context.mdc-keys=user.id,trace";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        ContractExceptionsTest.contracts(root, "account", AccountContract.class);
        ContractExceptionsTest.contracts(root, "payment", PaymentContract.class);
        ContractExceptionsTest.contracts(root, "boundary", BoundaryContract.class);
        ThreadTenant.HANDLED.set(0);
        Seen.AFTER.clear();
    }

    @AfterEach
    void clean() {
        MDC.clear();
        ThreadTenant.set(null);
    }

    /** What a server thread's own filter put before the module route, and what is left once the request ends. */
    static final class Seen extends OncePerRequestFilter {
        static final List<String> AFTER = new CopyOnWriteArrayList<>();

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            MDC.put("user.id", "from-filter");
            MDC.put("trace", "from-filter");
            HttpServletRequest seen = request;
            if (request.getHeader("X-User") != null) {
                Principal user = request.getHeader("X-User")::toString;
                seen = new HttpServletRequestWrapper(request) {
                    @Override
                    public Principal getUserPrincipal() {
                        return user;
                    }
                };
            }
            try {
                chain.doFilter(seen, response);
            } finally {
                AFTER.add(MDC.get("user.id") + "|" + MDC.get("trace") + "|" + ThreadTenant.tenant());
                MDC.remove("user.id");
                MDC.remove("trace");
            }
        }
    }

    /** A host route outside every module, reading what a module route would see. */
    @RestController
    static class Probe {
        @GetMapping("/probe")
        String probe() {
            return MDC.get("user.id") + "|" + MDC.get("other") + "|" + ThreadTenant.tenant();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(Probe.class)
    static class Probes {
        @Bean
        Seen seen() {
            return new Seen();
        }
    }

    record Holder(AccountContract account) {
    }

    @Configuration(proxyBeanMethods = false)
    static class Holders {
        @Bean
        Holder holder(AccountContract account) {
            return new Holder(account);
        }
    }

    private static final String[] NONE = {"spring.liquibase.enabled=false", "topolith.modules.account.mode=none",
            "topolith.modules.payment.mode=none", "topolith.modules.boundary.mode=none"};

    /** A server host with no tenant bean and no listed key. */
    private WebApplicationContextRunner bare(Fixtures.RecordingLoader loader, String... properties) {
        return new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                .withClassLoader(loader).withUserConfiguration(ErrorHost.class, Holders.class, Probes.class)
                .withPropertyValues("server.port=0").withPropertyValues(NONE).withPropertyValues(properties);
    }

    private WebApplicationContextRunner server(Fixtures.RecordingLoader loader, String... properties) {
        return bare(loader, properties).withBean(ThreadTenant.class).withPropertyValues(KEYS)
                .withPropertyValues(properties);
    }

    /** A host that serves the exposed internal account module over HTTP, with the context beans. */
    private void provider(Consumer<AssertableWebApplicationContext> check, String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=internal", "topolith.modules.account.expose.enabled=true")
                    .withPropertyValues(properties).run(context -> {
                        assertNull(context.getStartupFailure());
                        check.accept(context);
                    });
        }
    }

    private static HttpResponse<String> get(AssertableWebApplicationContext context, String path, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + ProviderErrorsTest.port(context) + path)).timeout(Duration.ofSeconds(10));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The caller's context, then what the provider sees of it through {@code account}, for any mode. */
    private static void assertCarried(AccountContract account, String unlisted) {
        MDC.put("user.id", "Zoë é/ü %");
        ThreadTenant.set("acme corp");
        // trace has no caller value: the provider sees none, even though its server filter put one
        assertEquals("Zoë é/ü %|null|null|acme corp", account.find("ctx"));
        ThreadTenant.set(null);
        assertEquals("Zoë é/ü %|null|null|null", account.find("ctx"));
        MDC.remove("user.id");
        MDC.put("trace", "t-1");
        MDC.put("other", "unlisted");
        // an unlisted key reaches the provider in internal mode only
        assertEquals("null|t-1|" + unlisted + "|null", account.find("ctx"));
    }

    @Test
    void ac_ext_48_internal_mode_sees_the_same_values() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ClientHost.class)
                    .withBean(ThreadTenant.class).withPropertyValues(NONE).withPropertyValues(KEYS,
                            "topolith.modules.account.mode=internal").run(context -> {
                                assertNull(context.getStartupFailure());
                                AccountContract account = context.getBean(AccountContract.class);
                                assertCarried(account, "unlisted");
                            });
        }
    }

    @Test
    void ac_ext_48_loopback_mode_carries_the_listed_keys_and_the_tenant() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=loopback").run(context -> {
                assertNull(context.getStartupFailure());
                assertCarried(context.getBean(Holder.class).account(), "null");
            });
        }
    }

    @Test
    void ac_ext_48_external_mode_carries_the_listed_keys_and_the_tenant() throws Exception {
        provider(served -> {
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ClientHost.class)
                        .withBean(ThreadTenant.class).withPropertyValues(NONE).withPropertyValues(KEYS,
                                "topolith.modules.account.mode=external", "topolith.modules.account.remote.url=http://"
                                        + "127.0.0.1:" + ProviderErrorsTest.port(served),
                                "topolith.modules.account.remote.read-timeout=5s").run(context -> {
                                    assertNull(context.getStartupFailure());
                                    assertCarried(context.getBean(AccountContract.class), "null");
                                });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void ac_ext_49_the_provider_thread_is_restored_after_a_normal_and_a_failed_request() throws Exception {
        provider(context -> {
            String sent[] = {"Topolith-Mdc-user.id", "alice", "Topolith-Tenant", "acme"};
            assertEquals(200, get(context, "/account/find?case=ctx", sent).statusCode());
            assertEquals(500, get(context, "/account/find?case=ctxfail", sent).statusCode());
            // the filter's own values are back, and no tenant is left on the thread
            assertEquals(List.of("from-filter|from-filter|null", "from-filter|from-filter|null"), Seen.AFTER);
        });
    }

    private static final String CARRIED = "alice|null|null|acme";
    private static final String[] SENT = {"Topolith-Mdc-user.id", "alice", "Topolith-Tenant", "acme"};

    @Test
    void ac_ext_49_an_async_module_route_leaves_nothing_on_the_container_thread() throws Exception {
        // one container thread, so a value left behind by the first request is seen by the second
        provider(context -> {
            assertEquals(CARRIED, get(context, "/account/async", SENT).body());
            assertEquals("from-filter|null|null", get(context, "/probe").body());
            assertEquals("from-filter|from-filter|null", Seen.AFTER.get(0));
        }, "server.tomcat.threads.max=1", "server.tomcat.threads.min-spare=1");
    }

    @Test
    void ac_ext_49_a_forward_between_module_routes_restores_the_outer_state() throws Exception {
        provider(context -> {
            assertEquals(CARRIED, get(context, "/account/forward", SENT).body());
            assertEquals(List.of("from-filter|from-filter|null"), Seen.AFTER);
        });
    }

    @Test
    void ac_ext_50_a_route_outside_every_module_ignores_the_headers() throws Exception {
        provider(context -> {
            HttpResponse<String> answer = get(context, "/probe", "Topolith-Mdc-user.id", "alice", "Topolith-Tenant",
                    "acme", "Topolith-Mdc-other", "x");
            // the server filter's value is untouched and no tenant was opened
            assertEquals("from-filter|null|null", answer.body());
        }, "topolith.context.mdc-keys=user.id,trace,other");
    }

    @Test
    void ac_ext_50_a_module_route_ignores_the_header_of_an_unlisted_key() throws Exception {
        provider(context -> {
            HttpResponse<String> answer = get(context, "/account/find?case=ctx", "Topolith-Mdc-other", "x",
                    "topolith-mdc-USER.ID", "alice");
            assertEquals("alice|null|null|null", answer.body());
        });
    }

    @Test
    void ac_ext_50_a_header_that_does_not_decode_or_is_over_the_limit_answers_400_without_the_provider()
            throws Exception {
        provider(context -> {
            String[][] bad = {{"Topolith-Mdc-user.id", "%zz"}, {"Topolith-Mdc-user.id", "%4"},
                    {"Topolith-Mdc-user.id", "%FF"}, {"Topolith-Mdc-user.id", "a b"},
                    {"Topolith-Mdc-user.id", "a".repeat(1025)}, {"Topolith-Tenant", "%"},
                    {"Topolith-Tenant", "a".repeat(1025)}};
            for (String[] header : bad) {
                assertEquals(400, get(context, "/account/find?case=ctx", header).statusCode(), header[0]);
            }
            assertEquals(0, ThreadTenant.HANDLED.get());
            // a header at the limit is accepted
            assertEquals(200, get(context, "/account/find?case=ctx", "Topolith-Mdc-user.id", "a".repeat(1024))
                    .statusCode());
        });
    }

    @Test
    void ac_ext_58_open_receives_the_request_principal_and_a_rejection_answers_403() throws Exception {
        provider(context -> {
            assertEquals(200, get(context, "/account/find?case=ctx", "Topolith-Tenant", "acme").statusCode());
            assertEquals("anonymous", ThreadTenant.lastPrincipal);
            assertEquals(200, get(context, "/account/find?case=ctx", "Topolith-Tenant", "acme", "X-User", "alice")
                    .statusCode());
            assertEquals("alice", ThreadTenant.lastPrincipal);
            ThreadTenant.HANDLED.set(0);
            HttpResponse<String> rejected = get(context, "/account/find?case=ctx", "Topolith-Tenant", "evil");
            assertEquals(403, rejected.statusCode());
            assertFalse(rejected.body().contains("secret"), rejected.body());
            assertEquals(0, ThreadTenant.HANDLED.get());
            // the rejected request leaves nothing behind either
            assertEquals("from-filter|from-filter|null", Seen.AFTER.get(Seen.AFTER.size() - 1));
        });
    }

    @Test
    void ac_ext_58_the_caller_receives_an_exception_neither_declared_nor_unavailable() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=loopback").run(context -> {
                assertNull(context.getStartupFailure());
                ThreadTenant.set("evil");
                RuntimeException thrown = assertThrows(RuntimeException.class,
                        () -> context.getBean(Holder.class).account().find("ctx"));
                assertInstanceOf(RestClientResponseException.class, thrown);
                assertFalse(thrown instanceof ModuleUnavailableException);
                assertEquals(403, ((RestClientResponseException) thrown).getStatusCode().value());
                assertEquals(0, ThreadTenant.HANDLED.get());
            });
        }
    }

    @Test
    void ac_ext_51_an_mdc_value_over_the_limit_is_not_sent_and_the_log_names_the_key(CapturedOutput output)
            throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=loopback").run(context -> {
                assertNull(context.getStartupFailure());
                // 1025 characters once encoded
                String value = "x".repeat(1025);
                MDC.put("user.id", value);
                MDC.put("trace", "kept");
                assertEquals("null|kept|null|null", context.getBean(Holder.class).account().find("ctx"));
                assertTrue(output.getAll().contains("TL0035"), output.getAll());
                assertTrue(output.getAll().contains("user.id"));
                assertFalse(output.getAll().contains(value));
            });
        }
    }

    @Test
    void ac_ext_51_a_tenant_over_the_limit_fails_the_call_with_tl0038_undeclared() throws Exception {
        CallObservationTest.Recorder recorder = new CallObservationTest.Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=loopback")
                    .withBean(ObservationRegistry.class, recorder::registry).run(context -> {
                        assertNull(context.getStartupFailure());
                        ThreadTenant.set("t".repeat(1025));
                        TopolithException thrown = assertThrows(TopolithException.class,
                                () -> context.getBean(Holder.class).account().find("ctx"));
                        assertEquals(TopolithCode.TL0038, thrown.diagnostics().get(0).code());
                        assertEquals(0, ThreadTenant.HANDLED.get());
                        assertEquals(1, recorder.calls().size());
                        assertEquals("undeclared", recorder.calls().get(0).getLowCardinalityKeyValue("exception")
                                .getValue());
                        // the 1024th character is the last allowed
                        ThreadTenant.set("t".repeat(1024));
                        assertTrue(context.getBean(Holder.class).account().find("ctx")
                                .endsWith("|" + "t".repeat(1024)));
                    });
        }
    }

    private static TopolithCode failure(AssertableApplicationContext context) {
        Throwable cause = context.getStartupFailure();
        while (cause != null && !(cause instanceof TopolithException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, String.valueOf(context.getStartupFailure()));
        return ((TopolithException) cause).diagnostics().get(0).code();
    }

    private void startup(ClassLoader parent, String key, int tenants, Consumer<AssertableApplicationContext> check)
            throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(parent, root)) {
            ApplicationContextRunner runner = new ApplicationContextRunner().withClassLoader(loader)
                    .withUserConfiguration(ClientHost.class).withPropertyValues(NONE)
                    .withPropertyValues("topolith.modules.account.mode=internal");
            if (key != null) {
                runner = runner.withPropertyValues("topolith.context.mdc-keys=" + key);
            }
            for (int i = 0; i < tenants; i++) {
                runner = runner.withBean("tenant" + i, ThreadTenant.class);
            }
            runner.run(check::accept);
        }
    }

    private void startup(String key, int tenants, Consumer<AssertableApplicationContext> check) throws Exception {
        startup(Fixtures.class.getClassLoader(), key, tenants, check);
    }

    @Test
    void ac_ext_52_an_invalid_key_two_keys_equal_ignoring_case_and_too_many_keys_fail_with_tl0034()
            throws Exception {
        for (String key : List.of("-x", "a b", "a/b", "user.id,User.Id", "k1,k2,k3,k4,k5,k6,k7,k8,k9,k10,k11,k12,"
                + "k13,k14,k15,k16,k17")) {
            startup(key, 0, context -> assertEquals(TopolithCode.TL0034, failure(context), key));
        }
        startup("k1,k2,k3,k4,k5,k6,k7,k8,k9,k10,k11,k12,k13,k14,k15,k16", 0,
                context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void ac_ext_52_a_listed_key_without_slf4j_fails_with_tl0034() throws Exception {
        startup(new FilteredClassLoader("org.slf4j.MDC"), "user.id", 0,
                context -> assertEquals(TopolithCode.TL0034, failure(context)));
    }

    @Test
    void ac_ext_52_two_tenant_context_beans_fail_with_tl0034() throws Exception {
        startup(null, 2, context -> assertEquals(TopolithCode.TL0034, failure(context)));
        startup(null, 1, context -> assertEquals(1, context.getBeansOfType(ContextPropagation.class).size()));
    }

    @Test
    void ac_ext_52_with_no_key_and_no_tenant_bean_nothing_is_installed() throws Exception {
        startup(null, 0, context -> {
            assertNull(context.getStartupFailure());
            // nothing is active, so no client adds a header and no route opens anything
            assertFalse(context.getBean(ContextPropagation.class).active());
            assertEquals(Map.of(), context.getBean(ContextPropagation.class).headers());
        });
    }

    @Test
    void ac_ext_52_a_none_module_gets_no_propagation_even_with_a_key_and_a_tenant_bean() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ClientHost.class)
                    .withBean(ThreadTenant.class).withPropertyValues(NONE).withPropertyValues(KEYS)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals(0, context.getBeansOfType(ContextPropagation.class).size());
                    });
        }
    }

    /** Runs every call of the adapter on another thread, as a circuit breaker's time limiter does. */
    @Configuration(proxyBeanMethods = false)
    static class Hop {
        private static final ExecutorService HOP = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "hop");
            thread.setDaemon(true);
            return thread;
        });

        @Bean
        RestClientHttpServiceGroupConfigurer hopToAnotherThread() {
            return groups -> groups.filterByName("account").forEachProxyFactory((group, factory) -> factory
                    .exchangeAdapterDecorator(adapter -> (HttpExchangeAdapter) Proxy.newProxyInstance(
                            HttpExchangeAdapter.class.getClassLoader(), new Class<?>[] {HttpExchangeAdapter.class},
                            (proxy, method, args) -> {
                                try {
                                    return HOP.submit(() -> method.invoke(adapter, args)).get();
                                } catch (ExecutionException e) {
                                    throw e.getCause() instanceof InvocationTargetException invoked
                                            ? invoked.getCause() : e.getCause();
                                }
                            })));
        }
    }

    @Test
    void ac_ext_48_a_call_whose_exchange_runs_on_another_thread_still_carries_the_callers_context()
            throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=loopback").withUserConfiguration(Hop.class)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        AccountContract account = context.getBean(Holder.class).account();
                        MDC.put("user.id", "alice");
                        ThreadTenant.set("acme");
                        assertEquals(CARRIED, account.find("ctx"));
                        // the tenant check also runs on the calling thread, so the caller gets the exception itself
                        ThreadTenant.set("t".repeat(1025));
                        TopolithException thrown = assertThrows(TopolithException.class,
                                () -> account.find("ctx"));
                        assertEquals(TopolithCode.TL0038, thrown.diagnostics().get(0).code());
                    });
        }
    }

    /** Registers a tenant context only after every configuration class is parsed, as a later auto-configuration. */
    @Configuration(proxyBeanMethods = false)
    static class Late {
        @Bean
        static BeanDefinitionRegistryPostProcessor lateTenant() {
            return new BeanDefinitionRegistryPostProcessor() {
                @Override
                public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                    registry.registerBeanDefinition("lateTenantContext", new RootBeanDefinition(ThreadTenant.class));
                }

                @Override
                public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
                }
            };
        }
    }

    @Test
    void ac_ext_52_a_tenant_context_registered_after_topoliths_configuration_still_propagates() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            bare(loader, "topolith.modules.account.mode=loopback").withUserConfiguration(Late.class)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        ThreadTenant.set("acme");
                        // no key is listed, so the server filter's own MDC values stay; the tenant crosses
                        assertEquals("from-filter|from-filter|null|acme",
                                context.getBean(Holder.class).account().find("ctx"));
                    });
        }
    }

    @Test
    void ac_ext_52_a_host_without_the_servlet_api_starts_with_an_internal_module_and_a_listed_key()
            throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(
                new FilteredClassLoader("jakarta.servlet"), root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ClientHost.class)
                    .withBean(ThreadTenant.class).withPropertyValues(NONE).withPropertyValues(KEYS,
                            "topolith.modules.boundary.mode=internal")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertTrue(context.getBean(ContextPropagation.class).active());
                    });
        }
    }
}
