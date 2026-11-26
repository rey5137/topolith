package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.AccountController;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import io.github.rey5137.topolith.spring.boot.loopfixture.host.GroupHost;
import io.github.rey5137.topolith.spring.boot.loopfixture.host.LoopbackHost;
import io.github.rey5137.topolith.spring.boot.loopfixture.host.OtherClient;
import io.github.rey5137.topolith.spring.boot.loopfixture.host.WalletCaller;
import io.github.rey5137.topolith.spring.boot.loopfixture.shop.ShopService;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.PlainWallet;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.PrimaryWalletConfig;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.PriorityWallet;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.SubPriorityWallet;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.WalletController;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.WalletInside;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api.Balance;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api.WalletContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** M5.3: a loopback module runs in-process and its callers reach it over HTTP on the host's own server (R-MODE-06). */
@ExtendWith(OutputCaptureExtension.class)
class LoopbackTest {
    private static final String LOOP = "io.github.rey5137.topolith.spring.boot.loopfixture";

    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        descriptors(root, "wallet", WalletContract.class.getName());
        descriptors(root, "shop");
    }

    /** The API and module descriptors of {@code id} under {@link #LOOP}. */
    static void descriptors(Path root, String id, String... provides) throws IOException {
        String api = LOOP + "." + id + ".api";
        write(root, "apis/" + id, out -> Descriptors.write(ApiDescriptor.builder(id).apiPackages(List.of(api))
                .apiVersion("1.0.0").provides(List.of(provides)).build(), out));
        write(root, "modules/" + id, out -> Descriptors.write(ModuleDescriptor.builder(id).packageName(LOOP + "." + id)
                .apiPackages(List.of(api)).apiVersion("1.0.0").provides(List.of(provides)).build(), out));
    }

    private interface Body {
        void write(Writer out) throws IOException;
    }

    private static void write(Path root, String name, Body body) throws IOException {
        Path file = root.resolve("META-INF/topolith/" + name + ".properties");
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            body.write(out);
        }
    }

    static WebApplicationContextRunner server(Class<?> host) {
        return new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                .withUserConfiguration(host)
                .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                        "topolith.modules.wallet.mode=loopback", "topolith.modules.shop.mode=internal");
    }

    /** The loopback host on a started server, with the wallet module loopback and the shop module internal. */
    private void serve(Consumer<AssertableWebApplicationContext> check, String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(LoopbackHost.class).withClassLoader(loader).withPropertyValues(properties).run(context -> {
                assertNull(context.getStartupFailure());
                check.accept(context);
            });
        }
    }

    private static WalletContract client(AssertableWebApplicationContext context) {
        return context.getBean(WalletCaller.class).wallet;
    }

    private static int port(AssertableWebApplicationContext context) {
        return ProviderErrorsTest.port(context);
    }

    static TopolithException failure(Throwable failure) {
        assertNotNull(failure, "the context should have failed to start");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TopolithException topolith) {
                return topolith;
            }
        }
        return fail("no TopolithException in the cause chain of " + failure);
    }

    static TopolithException fails(Throwable startup, TopolithCode code, String... texts) {
        TopolithException failure = failure(startup);
        assertEquals(List.of(code), failure.diagnostics().stream().map(d -> d.code()).distinct().toList(),
                failure.getMessage());
        for (String text : texts) {
            assertTrue(failure.getMessage().contains(text), failure.getMessage());
        }
        return failure;
    }

    @Test
    void ac_mode_41_callers_outside_the_module_get_the_http_client_with_no_primary_bean() throws Exception {
        serve(context -> {
            WalletContract host = client(context);
            WalletContract shop = context.getBean(ShopService.class).wallet;
            assertFalse(host instanceof WalletController);
            assertFalse(AopUtils.getTargetClass(host) == WalletController.class);
            assertFalse(shop instanceof WalletController, "another internal module gets the client");
            assertSame(host, shop);
            assertEquals("late", host.slow(0));
            DefaultListableBeanFactory factory = (DefaultListableBeanFactory) context.getBeanFactory();
            for (String name : factory.getBeanNamesForType(WalletContract.class)) {
                assertFalse(((AbstractBeanDefinition) factory.getBeanDefinition(name)).isPrimary(), name);
            }
            assertEquals(2, factory.getBeanNamesForType(WalletContract.class).length);
        });
    }

    @Test
    void ac_mode_48_inside_the_module_the_concrete_class_gets_the_provider() throws Exception {
        serve(context -> assertInstanceOf(WalletController.class, context.getBean(WalletInside.class).concrete));
    }

    @Test
    void ac_mode_49_a_list_of_another_interface_still_holds_the_provider() throws Exception {
        serve(context -> {
            WalletCaller caller = context.getBean(WalletCaller.class);
            assertEquals(1, caller.audits.size());
            assertInstanceOf(WalletController.class, caller.audits.get(0));
        });
    }

    @Test
    void ac_mode_50_the_modules_own_injection_of_its_contract_gets_the_provider() throws Exception {
        serve(context -> {
            WalletInside inside = context.getBean(WalletInside.class);
            assertInstanceOf(WalletController.class, inside.contract);
            assertSame(inside.concrete, inside.contract);
        });
    }

    @Test
    void ac_mode_42_routes_are_served_under_the_id_and_matched_by_the_exposed_matcher() throws Exception {
        for (String expose : List.of("", "topolith.modules.wallet.expose.enabled=true")) {
            serve(context -> {
                ProviderErrorsTest.Answer answer = ProviderErrorsTest.get(context, "/wallet/slow?millis=0");
                assertEquals(200, answer.status(), expose);
                assertEquals("late", answer.body());
                assertEquals(404, ProviderErrorsTest.get(context, "/slow?millis=0").status());
                assertEquals(Set.of("wallet"), context.getBean(ExposedEndpoints.class).moduleIds());
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/wallet/slow");
                request.setRequestURI("/wallet/slow");
                assertTrue(context.getBean(ExposedRequestMatcher.class).matches(request));
            }, expose);
        }
    }

    @Test
    void ac_mode_42_expose_enabled_false_fails_with_tl0003() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(LoopbackHost.class).withClassLoader(loader)
                    .withPropertyValues("topolith.modules.wallet.expose.enabled=false")
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0003,
                            "topolith.modules.wallet.expose.enabled", "Remove"));
        }
    }

    @Test
    void ac_mode_43_the_client_calls_the_local_port_under_the_address_and_context_path() throws Exception {
        serve(context -> assertEquals("http://localhost:" + port(context) + "/wallet",
                context.getBean(LoopbackEndpoints.class).baseUrl("wallet")));
        serve(context -> {
            assertEquals("http://localhost:" + port(context) + "/wallet",
                    context.getBean(LoopbackEndpoints.class).baseUrl("wallet"));
            assertEquals("late", client(context).slow(0));
        }, "server.address=0.0.0.0");
        serve(context -> {
            assertEquals("http://127.0.0.1:" + port(context) + "/app/wallet",
                    context.getBean(LoopbackEndpoints.class).baseUrl("wallet"));
            assertEquals("late", client(context).slow(0));
        }, "server.address=127.0.0.1", "server.servlet.context-path=/app");
        LoopbackEndpoints endpoints = new LoopbackEndpoints(environment("server.address=::1"));
        endpoints.listen(8080, "/");
        assertEquals("http://[0:0:0:0:0:0:0:1]:8080/wallet", endpoints.baseUrl("wallet"));
    }

    private static MockEnvironment environment(String... properties) {
        MockEnvironment environment = new MockEnvironment();
        for (String property : properties) {
            String[] pair = property.split("=", 2);
            environment.setProperty(pair[0], pair[1]);
        }
        return environment;
    }

    @Test
    void ac_mode_43_the_client_calls_under_the_dispatcher_servlet_path() throws Exception {
        serve(context -> {
            assertEquals("http://localhost:" + port(context) + "/app/api/wallet",
                    context.getBean(LoopbackEndpoints.class).baseUrl("wallet"));
            assertEquals("late", client(context).slow(0));
        }, "spring.mvc.servlet.path=/api", "server.servlet.context-path=/app");
        serve(context -> assertEquals("late", client(context).slow(0)), "spring.mvc.servlet.path=/");
    }

    @Test
    void ac_mode_43_a_call_before_the_server_listens_is_a_connection_failure() {
        LoopbackEndpoints endpoints = new LoopbackEndpoints(environment());
        RestClient.Builder builder = RestClient.builder();
        endpoints.install("wallet", builder, new SimpleClientHttpRequestFactory());
        assertNull(endpoints.baseUrl("wallet"));
        WalletContract proxy = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build())).build()
                .createClient(WalletContract.class);
        ProxyFactory factory = new ProxyFactory(proxy);
        factory.addAdvice(new ContractCallTranslator("wallet", Set.of(LOOP + ".wallet.api"),
                getClass().getClassLoader()));
        WalletContract client = (WalletContract) factory.getProxy();
        ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class, () -> client.slow(0));
        assertEquals("module 'wallet' could not be reached (connection failure)", e.getMessage());
    }

    @Test
    void ac_mode_44_no_listening_plain_http_servlet_server_fails_with_tl0026() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            String[] modes = {"spring.liquibase.enabled=false", "topolith.modules.wallet.mode=loopback",
                    "topolith.modules.shop.mode=internal"};
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(LoopbackHost.class)
                    .withPropertyValues(modes)
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0026, "no servlet web server",
                            "RANDOM_PORT"));
            new WebApplicationContextRunner().withClassLoader(loader).withUserConfiguration(LoopbackHost.class)
                    .withPropertyValues(modes)
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0026, "MOCK"));
            server(LoopbackHost.class).withClassLoader(loader).withPropertyValues("server.port=-1")
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0026, "server.port is -1"));
            server(LoopbackHost.class).withClassLoader(loader).withPropertyValues("server.ssl.enabled=true")
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0026, "SSL"));
            server(LoopbackHost.class).withClassLoader(loader).withPropertyValues("server.ssl.bundle=missing")
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0026, "SSL"));
            server(LoopbackHost.class).withClassLoader(loader)
                    .withPropertyValues("server.ssl.bundle=missing", "server.ssl.enabled=false")
                    .run(context -> assertNull(context.getStartupFailure()));
            new ReactiveWebApplicationContextRunner().withClassLoader(loader).withUserConfiguration(LoopbackHost.class)
                    .withPropertyValues(modes)
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0028, "loopback"));
        }
    }

    @Test
    void ac_mode_45_remote_settings_and_a_boot_base_url_contradict_loopback_with_tl0003() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            for (String key : List.of("topolith.modules.wallet.remote.url",
                    "topolith.modules.wallet.remote.service-id", "spring.http.serviceclient.wallet.base-url")) {
                String value = key.endsWith("service-id") ? "wallet-service" : "http://localhost:1";
                server(LoopbackHost.class).withClassLoader(loader).withPropertyValues(key + "=" + value)
                        .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0003, key, "Remove " + key));
            }
            server(LoopbackHost.class).withClassLoader(loader)
                    .withPropertyValues("topolith.modules.wallet.remote.url= ",
                            "topolith.modules.wallet.remote.service-id=")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals("late", client(context).slow(0));
                    });
            server(GroupHost.class).withClassLoader(loader)
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0003, "OtherClient",
                            "loopback module 'wallet'"));
        }
    }

    @Test
    void ac_mode_45_a_boot_base_url_from_an_environment_variable_fails_with_tl0003() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(LoopbackHost.class).withClassLoader(loader).withInitializer(context ->
                    context.getEnvironment().getPropertySources().addLast(new SystemEnvironmentPropertySource(
                            "systemEnvironment", Map.of("SPRING_HTTP_SERVICECLIENT_WALLET_BASEURL",
                                    "http://localhost:1"))))
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0003,
                            "spring.http.serviceclient.wallet.base-url"));
        }
    }

    @Test
    void ac_mode_53_the_placeholder_base_url_of_a_loopback_group_is_not_a_user_setting() throws Exception {
        serve(context -> {
            assertEquals("http://localhost/wallet",
                    context.getEnvironment().getProperty("spring.http.serviceclient.wallet.base-url"));
            assertEquals("late", client(context).slow(0));
        });
    }

    @Test
    void ac_mode_54_a_by_type_lookup_outside_the_module_is_ambiguous() throws Exception {
        serve(context -> {
            assertThrows(NoUniqueBeanDefinitionException.class, () -> context.getBean(WalletContract.class));
            assertNull(context.getBeanProvider(WalletContract.class).getIfUnique());
            assertEquals(2, context.getBeansOfType(WalletContract.class).size());
        });
    }

    @Test
    void ac_mode_54_a_primary_or_priority_loopback_provider_fails_with_tl0003() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(LoopbackHost.class).withClassLoader(loader).withUserConfiguration(PrimaryWalletConfig.class)
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0003, "primaryWallet",
                            "'wallet'", "@Primary", "Remove @Primary", "mode to internal"));
            server(LoopbackHost.class).withClassLoader(loader)
                    .withBean("plainWallet", PlainWallet.class, PlainWallet::new, definition -> definition
                            .setPrimary(true))
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0003, "plainWallet",
                            "@Primary"));
            server(LoopbackHost.class).withClassLoader(loader)
                    .withBean("priorityWallet", PriorityWallet.class, PriorityWallet::new)
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0003, "priorityWallet",
                            "@Priority", "Remove @Priority"));
            server(LoopbackHost.class).withClassLoader(loader)
                    .withBean("subWallet", SubPriorityWallet.class, SubPriorityWallet::new)
                    .run(context -> fails(context.getStartupFailure(), TopolithCode.TL0003, "subWallet",
                            "@Priority"));
            server(LoopbackHost.class).withClassLoader(loader).withPropertyValues("topolith.modules.wallet.mode=internal")
                    .withBean("plainWallet", PlainWallet.class, PlainWallet::new, definition -> definition
                            .setPrimary(true))
                    .run(context -> assertNull(context.getStartupFailure()));
        }
    }

    @Test
    void ac_mode_46_without_a_loopback_module_nothing_of_loopback_is_registered() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            for (String mode : List.of("internal", "none")) {
                server(GroupHost.class).withClassLoader(loader)
                        .withPropertyValues("topolith.modules.wallet.mode=" + mode, "topolith.modules.shop.mode=none")
                        .run(context -> {
                            assertNull(context.getStartupFailure());
                            assertTrue(context.getBeansOfType(LoopbackEndpoints.class).isEmpty(), mode);
                            assertFalse(context.containsBean("topolithLoopbackCandidates"), mode);
                            assertFalse(context.containsBean("topolithLoopbackClients"), mode);
                            assertFalse(((DefaultListableBeanFactory) context.getBeanFactory())
                                    .getAutowireCandidateResolver() instanceof LoopbackCandidateResolver, mode);
                            assertEquals(1, context.getBeanNamesForType(OtherClient.class).length, mode);
                            assertEquals(mode.equals("internal") ? 1 : 0,
                                    context.getBeanNamesForType(WalletContract.class).length, "no wallet client");
                        });
            }
            server(GroupHost.class).withClassLoader(loader)
                    .withPropertyValues("topolith.modules.wallet.mode=none", "topolith.modules.shop.mode=none")
                    .run(context -> {
                        assertEquals(0, context.getBeanNamesForType(WalletContract.class).length);
                        assertEquals(List.of(), loader.requested(LOOP + ".wallet"));
                        assertEquals(404, ProviderErrorsTest.get(context, "/wallet/slow?millis=0").status());
                    });
        }
    }

    @Test
    void ac_ext_33_a_loopback_call_copies_arguments_and_results() throws Exception {
        serve(context -> {
            WalletContract wallet = client(context);
            Balance argument = new Balance(21);
            Balance result = wallet.deposit(argument);
            assertEquals(42, result.getAmount());
            assertEquals(List.of("returned"), result.getNotes());
            assertEquals(List.of(), argument.getNotes(), "the provider changed only its own copy");
            wallet.touch();
            assertEquals(42, result.getAmount(), "a later change to the returned object stays on the provider");
            assertEquals(List.of("returned"), result.getNotes());
        });
    }

    @Test
    void ac_ext_34_a_loopback_call_meets_the_external_contract() throws Exception {
        Path accounts = Fixtures.dir(temp, "accounts");
        ContractExceptionsTest.contracts(accounts, "account", AccountContract.class);
        ContractExceptionsTest.contracts(accounts, "payment");
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(accounts)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                            "topolith.modules.account.mode=loopback", "topolith.modules.payment.mode=none")
                    .withBean(AccountCaller.class)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        AccountContract account = context.getBean(AccountCaller.class).account;
                        assertFalse(account instanceof AccountController);
                        ContractParityTest.assertContract(account);
                    });
        }
        serve(context -> {
            ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                    () -> client(context).slow(3000));
            assertEquals("module 'wallet' could not be reached (timeout)", e.getMessage());
        }, "topolith.modules.wallet.remote.read-timeout=1s");
    }

    /** A host bean of the account parity check. */
    static class AccountCaller {
        final AccountContract account;

        AccountCaller(AccountContract account) {
            this.account = account;
        }
    }

    @Test
    void ac_ext_35_a_loopback_call_inside_a_caller_transaction_runs_outside_it() throws Exception {
        serve(context -> {
            WalletContract wallet = client(context);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            String caller = Thread.currentThread().getName();
            transaction.executeWithoutResult(status -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                // one call: the provider runs on a server request thread, inside a servlet request, with no
                // transaction; an in-process call would run on the caller's thread with no request
                String[] seen = wallet.transaction().split("\\|");
                assertFalse(caller.equals(seen[0]), "the provider runs on a server request thread: " + seen[0]);
                assertEquals("request", seen[1], "the call reached the module's route over HTTP");
                assertEquals("false", seen[2]);
            });
            String[] inProcess = context.getBean(WalletInside.class).contract.transaction().split("\\|");
            assertEquals(caller + "|none", inProcess[0] + "|" + inProcess[1], "the check tells an in-process call");
        });
    }

    @Test
    void ac_ext_40_a_loopback_group_uses_the_default_timeouts_and_its_overrides() throws Exception {
        serve(context -> {
            String group = "spring.http.serviceclient.wallet.";
            assertEquals("2s", context.getEnvironment().getProperty(group + "connect-timeout"));
            assertEquals("5s", context.getEnvironment().getProperty(group + "read-timeout"));
            assertEquals("http://localhost/wallet", context.getEnvironment().getProperty(group + "base-url"));
        });
        serve(context -> {
            assertEquals("3s", context.getEnvironment()
                    .getProperty("spring.http.serviceclient.wallet.connect-timeout"));
            assertEquals("late", client(context).slow(0));
            assertEquals("timeout", assertThrows(ModuleUnavailableException.class, () -> client(context).slow(2000))
                    .getMessage().replaceAll(".*\\((.*)\\)", "$1"));
        }, "topolith.modules.wallet.remote.connect-timeout=3s", "topolith.modules.wallet.remote.read-timeout=1s");
    }

    @Test
    void ac_ext_41_module_clients_configure_applies_to_a_loopback_group() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(LoopbackHost.class).withClassLoader(loader)
                    .withBean("walletClientCustomizer", RestClientHttpServiceGroupConfigurer.class,
                            () -> ModuleClients.configure("wallet", builder -> builder.defaultHeader("X-Loopback",
                                    "customized")))
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals("customized", client(context).header());
                        assertEquals("", context.getBean(WalletInside.class).contract.header(),
                                "an in-module call is not an HTTP call");
                    });
        }
    }

    /** Changes properties as a refresh would, then hands the keys to Topolith; a null value removes the key. */
    private static void refresh(AssertableWebApplicationContext context, String... pairs) {
        var sources = context.getSourceApplicationContext().getEnvironment().getPropertySources();
        if (sources.get("refreshed") == null) {
            sources.addFirst(new MapPropertySource("refreshed", new HashMap<>()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) sources.get("refreshed").getSource();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null) {
                values.remove(pairs[i]);
            } else {
                values.put(pairs[i], pairs[i + 1]);
            }
            keys.add(pairs[i]);
        }
        context.getBean(TopolithRefresh.class).apply(keys);
    }

    @Test
    void ac_mode_47_a_refreshed_loopback_client_setting_needs_a_restart(CapturedOutput output) throws Exception {
        String timeout = "topolith.modules.wallet.remote.read-timeout";
        String fault = "topolith.modules.wallet.loopback.fault.rate";
        serve(context -> {
            TopolithRefreshStatus status = context.getBean(TopolithRefreshStatus.class);
            refresh(context, timeout, "1s");
            assertTrue(output.getAll().contains("TL0010"), output.getAll());
            assertTrue(output.getAll().contains("loopback module 'wallet' keeps its client settings: the refreshed "
                    + timeout), output.getAll());
            assertTrue(status.restartPending());
            assertEquals(Set.of("wallet"), status.wiring());
            assertEquals("late", client(context).slow(1500), "the running client keeps its read timeout");
            refresh(context, fault, "0.5");
            assertTrue(output.getAll().contains("the refreshed " + fault), output.getAll());
            assertFalse(output.getAll().contains("TL0003"), "a loopback.* refresh does not mention TL0003");
            refresh(context, "topolith.modules.wallet.remote.url", "http://x");
            assertTrue(output.getAll().contains("TL0003"), output.getAll());
            refresh(context, "topolith.modules.wallet.remote.url", null);
            int before = output.getAll().length();
            refresh(context, timeout, null, fault, "0.25");
            String last = output.getAll().substring(before);
            assertTrue(last.contains("the refreshed " + fault), last);
            assertFalse(last.contains(timeout), "a key back at its running value is not named: " + last);
            assertTrue(status.restartPending(), "the fault key still differs from its running value");
            refresh(context, fault, null);
            assertFalse(status.restartPending());
        });
    }

    @Test
    void ac_mode_47_a_refresh_changing_a_mode_to_or_from_loopback_needs_a_restart(CapturedOutput output)
            throws Exception {
        String mode = "topolith.modules.wallet.mode";
        serve(context -> {
            TopolithRefreshStatus status = context.getBean(TopolithRefreshStatus.class);
            refresh(context, mode, "internal");
            assertTrue(output.getAll().contains("module 'wallet' runs in mode loopback and the refreshed "
                    + "configuration requests 'internal'"), output.getAll());
            assertEquals(Map.of("wallet", "internal"), status.requestedModes());
            refresh(context, mode, "loopback");
            assertFalse(status.restartPending());
        });
        serve(context -> {
            refresh(context, mode, "loopback");
            assertTrue(output.getAll().contains("module 'wallet' runs in mode internal and the refreshed "
                    + "configuration requests 'loopback'"), output.getAll());
            assertEquals(Map.of("wallet", "loopback"), context.getBean(TopolithRefreshStatus.class).requestedModes());
        }, mode + "=internal");
    }

    @Test
    void ac_mode_47_a_refreshed_remote_url_or_service_id_on_a_loopback_module_logs(CapturedOutput output)
            throws Exception {
        serve(context -> {
            TopolithRefreshStatus status = context.getBean(TopolithRefreshStatus.class);
            for (String key : List.of("topolith.modules.wallet.remote.url",
                    "topolith.modules.wallet.remote.service-id")) {
                int before = output.getAll().length();
                refresh(context, key, "http://elsewhere");
                String last = output.getAll().substring(before);
                assertTrue(last.contains("TL0010") && last.contains("the refreshed " + key)
                        && last.contains("TL0003"), last);
                assertTrue(status.restartPending());
                refresh(context, key, null);
                assertFalse(status.restartPending());
            }
        });
    }
}
