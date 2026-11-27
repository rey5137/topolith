package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.BoundaryClient;
import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.api.BoundaryContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ClientHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.payment.api.PaymentContract;
import io.github.rey5137.topolith.spring.boot.observation.DefaultModuleCallObservationConvention;
import io.github.rey5137.topolith.spring.boot.observation.ModuleCallContext;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.invoker.HttpExchangeAdapter;
import org.springframework.web.service.invoker.HttpRequestValues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M6.2: the one {@code topolith.module.call} observation of a contract call, in every mode (R-EXT-11, D-86..D-89). */
class CallObservationTest {
    /** Each case of {@link AccountContract#find} and the {@code exception} value every mode records for it. */
    private static final Map<String, String> CASES = new LinkedHashMap<>();

    static {
        CASES.put("ok", "none");
        CASES.put("missing", "AccountNotFoundException");
        CASES.put("locked", "AccountException");
        CASES.put("declined", "PaymentDeclinedException");
        CASES.put("unavailable", "ModuleUnavailableException");
        CASES.put("secret", "undeclared");
        CASES.put("response-status", "undeclared");
        CASES.put("status-annotated", "undeclared");
        CASES.put("access-denied", "undeclared");
        CASES.put("jdk-argument", "IllegalArgumentException");
        CASES.put("jdk-state", "IllegalStateException");
        CASES.put("jdk-unsupported", "UnsupportedOperationException");
        CASES.put("jdk-number", "IllegalArgumentException");
    }

    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        ContractExceptionsTest.contracts(root, "account", AccountContract.class);
        ContractExceptionsTest.contracts(root, "payment", PaymentContract.class);
        ContractExceptionsTest.contracts(root, "boundary", BoundaryContract.class);
    }

    /** Records every observation that stops, whatever its name. */
    static final class Recorder implements ObservationHandler<Observation.Context> {
        private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }

        ObservationRegistry registry() {
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(this);
            return registry;
        }

        void clear() {
            stopped.clear();
        }

        List<ModuleCallContext> calls() {
            return stopped.stream().filter(ModuleCallContext.class::isInstance).map(ModuleCallContext.class::cast)
                    .toList();
        }

        List<Observation.Context> named(String name) {
            return stopped.stream().filter(context -> name.equals(context.getName())).toList();
        }
    }

    private static String key(Observation.Context context, String name) {
        KeyValue value = context.getLowCardinalityKeyValue(name);
        return value == null ? null : value.getValue();
    }

    private static void assertKeys(ModuleCallContext call, String module, String mode, String caller,
            String exception) {
        assertKeys(call, module, mode, caller, "find", exception);
    }

    private static void assertKeys(ModuleCallContext call, String module, String mode, String caller,
            String method, String exception) {
        assertEquals("topolith.module.call", call.getName());
        assertEquals(module, key(call, "module.id"));
        assertEquals(mode, key(call, "module.mode"));
        assertEquals(caller, key(call, "caller.module"));
        assertEquals(AccountContract.class.getName(), key(call, "contract"));
        assertEquals(method, key(call, "method"));
        assertEquals(exception, key(call, "exception"));
        assertEquals("account AccountContract." + method, call.getContextualName());
    }

    /** Calls every case of {@code account} and checks the one observation each records. */
    private static void assertMatrix(Recorder recorder, AccountContract account, String mode) {
        for (Map.Entry<String, String> entry : CASES.entrySet()) {
            recorder.clear();
            Throwable thrown = null;
            try {
                account.find(entry.getKey());
            } catch (RuntimeException e) {
                thrown = e;
            }
            List<ModuleCallContext> calls = recorder.calls();
            assertEquals(1, calls.size(), mode + " " + entry.getKey());
            assertKeys(calls.get(0), "account", mode, ModuleCallContext.HOST, entry.getValue());
            // the observation's error is the exception the caller receives (D-87)
            assertSame(thrown, calls.get(0).getError(), mode + " " + entry.getKey());
            assertEquals("none".equals(entry.getValue()), thrown == null);
            if (entry.getKey().startsWith("jdk-")) {
                String suffix = entry.getKey().substring(4);
                assertEquals(suffix.equals("argument") ? "argument" : suffix, thrown.getMessage());
                Class<?> expected = switch (suffix) {
                    case "argument" -> IllegalArgumentException.class;
                    case "state" -> IllegalStateException.class;
                    case "unsupported" -> UnsupportedOperationException.class;
                    default -> mode.equals("internal") ? NumberFormatException.class : IllegalArgumentException.class;
                };
                assertSame(expected, thrown.getClass());
            }
        }
    }

    private static void assertChecked(Recorder recorder, AccountContract account, String mode) {
        for (String value : List.of("direct", "subclass", "api-child")) {
            recorder.clear();
            var failure = org.junit.jupiter.api.Assertions.assertThrows(
                    io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountCheckedException.class,
                    () -> account.checked(value));
            assertEquals(1, recorder.calls().size());
            ModuleCallContext call = recorder.calls().get(0);
            assertKeys(call, "account", mode, ModuleCallContext.HOST, "checked",
                    value.equals("api-child") ? "AccountChildCheckedException" : "AccountCheckedException");
            assertEquals(switch (value) {
                case "direct" -> "checked message";
                case "subclass" -> "checked subclass";
                default -> "checked child";
            }, failure.getMessage());
            if (value.equals("api-child")) {
                assertSame(io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountChildCheckedException
                        .class, failure.getClass());
            }
        }
    }

    /** Boot's own registry would otherwise exist in every host with the observation module. */
    private static final String NO_BOOT_REGISTRY = "spring.autoconfigure.exclude="
            + "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration";

    private ApplicationContextRunner runner(Fixtures.RecordingLoader loader, Class<?> host, Recorder recorder,
            String... properties) {
        ApplicationContextRunner runner = new ApplicationContextRunner().withClassLoader(loader)
                .withUserConfiguration(host).withPropertyValues("spring.liquibase.enabled=false",
                        "topolith.modules.account.mode=none", "topolith.modules.payment.mode=none",
                        "topolith.modules.boundary.mode=none")
                .withPropertyValues(properties);
        return recorder == null ? runner : runner.withBean(ObservationRegistry.class, recorder::registry);
    }

    @Test
    void ac_ext_70_and_73_internal_mode_records_one_observation() throws Exception {
        Recorder recorder = new Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            runner(loader, ClientHost.class, recorder, "topolith.modules.account.mode=internal",
                    "topolith.modules.payment.mode=internal").run(context -> {
                        assertNull(context.getStartupFailure());
                        AccountContract account = context.getBean(AccountContract.class);
                        assertMatrix(recorder, account, "internal");
                        assertChecked(recorder, account, "internal");
                    });
        }
    }

    @Test
    void ac_ext_43_the_caller_is_the_module_whose_class_calls_and_a_module_own_call_is_not_observed()
            throws Exception {
        Recorder recorder = new Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            runner(loader, ClientHost.class, recorder, "topolith.modules.account.mode=internal",
                    "topolith.modules.payment.mode=internal", "topolith.modules.boundary.mode=internal")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        // the account module's handler calls the payment contract: caller account, then host
                        assertEquals("paid ok", context.getBean(AccountContract.class).find("pay"));
                        List<ModuleCallContext> calls = recorder.calls();
                        assertEquals(2, calls.size());
                        assertEquals("payment", key(calls.get(0), "module.id"));
                        assertEquals("account", key(calls.get(0), "caller.module"));
                        assertEquals("account", key(calls.get(1), "module.id"));
                        assertEquals("_host", key(calls.get(1), "caller.module"));
                        // the boundary module calls its own contract: no observation; host code calling it: one
                        recorder.clear();
                        context.getBean(BoundaryClient.class).callPlain();
                        assertEquals(List.of(), recorder.calls());
                        context.getBean(BoundaryContract.class).plain();
                        assertEquals(1, recorder.calls().size());
                        assertEquals("_host", key(recorder.calls().get(0), "caller.module"));
                    });
        }
    }

    /** A host bean that holds the loopback client of the account contract. */
    record Holder(AccountContract account) {
    }

    @Configuration(proxyBeanMethods = false)
    static class Holders {
        @Bean
        Holder holder(AccountContract account) {
            return new Holder(account);
        }
    }

    private WebApplicationContextRunner server(Fixtures.RecordingLoader loader, Recorder recorder,
            String... properties) {
        WebApplicationContextRunner runner = new WebApplicationContextRunner(
                AnnotationConfigServletWebServerApplicationContext::new).withClassLoader(loader)
                .withUserConfiguration(ErrorHost.class, Holders.class, ProviderErrorsTest.MethodSecurity.class)
                .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                        "topolith.modules.account.mode=none", "topolith.modules.payment.mode=none",
                        "topolith.modules.boundary.mode=none")
                .withPropertyValues(properties);
        return runner.withBean(ObservationRegistry.class, recorder::registry);
    }

    @Test
    void ac_ext_70_and_73_loopback_mode_records_one_observation_on_the_caller_only() throws Exception {
        Recorder recorder = new Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, recorder, "topolith.modules.account.mode=loopback").run(context -> {
                assertNull(context.getStartupFailure());
                AccountContract account = context.getBean(Holder.class).account();
                assertMatrix(recorder, account, "loopback");
                assertChecked(recorder, account, "loopback");
                assertMethodSecurity(recorder, account, "loopback");
            });
        }
    }

    private static void assertMethodSecurity(Recorder recorder, AccountContract account, String mode) {
        recorder.clear();
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> account.find("method-security"));
        assertFalse(thrown instanceof ModuleUnavailableException);
        assertEquals(1, recorder.calls().size());
        assertKeys(recorder.calls().get(0), "account", mode, ModuleCallContext.HOST, "undeclared");
    }

    /** One observation for a default method, whatever the contract calls inside it (INV-6). */
    private static void assertDefaultMethod(Recorder recorder, AccountContract account, String mode) {
        recorder.clear();
        assertEquals("found", account.findTwice("ok"));
        assertEquals(1, recorder.calls().size(), mode);
        assertEquals("findTwice", key(recorder.calls().get(0), "method"));
        assertEquals("_host", key(recorder.calls().get(0), "caller.module"));
    }

    @Test
    void ac_ext_42_a_default_method_records_one_observation_in_every_mode() throws Exception {
        Recorder internal = new Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            runner(loader, ClientHost.class, internal, "topolith.modules.account.mode=internal").run(context -> {
                assertNull(context.getStartupFailure());
                assertDefaultMethod(internal, context.getBean(AccountContract.class), "internal");
            });
        }
        Recorder looped = new Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, looped, "topolith.modules.account.mode=loopback").run(context -> {
                assertNull(context.getStartupFailure());
                assertDefaultMethod(looped, context.getBean(Holder.class).account(), "loopback");
            });
        }
        Recorder caller = new Recorder();
        serveExposed(new Recorder(), context -> external("http://127.0.0.1:" + ProviderErrorsTest.port(context),
                caller, external -> assertDefaultMethod(caller, external.getBean(AccountContract.class),
                        "external")));
    }

    @Test
    void ac_ext_42_a_client_records_nothing_for_an_object_method() throws Exception {
        Recorder looped = new Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, looped, "topolith.modules.account.mode=loopback").run(context -> {
                assertNull(context.getStartupFailure());
                AccountContract account = context.getBean(Holder.class).account();
                // Spring's HTTP interface proxy rejects the call itself; it is no contract call
                assertThrows(IllegalStateException.class, account::toString);
                assertEquals(List.of(), looped.calls());
            });
        }
    }

    @Test
    void ac_ext_44_a_loopback_fault_sets_module_unavailable() throws Exception {
        Recorder recorder = new Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, recorder, "topolith.modules.account.mode=loopback",
                    "topolith.modules.account.loopback.fault.rate=1").run(context -> {
                        assertNull(context.getStartupFailure());
                        ModuleUnavailableException thrown = assertThrows(ModuleUnavailableException.class,
                                () -> context.getBean(Holder.class).account().find("ok"));
                        assertEquals(1, recorder.calls().size());
                        assertKeys(recorder.calls().get(0), "account", "loopback", "_host",
                                "ModuleUnavailableException");
                        assertSame(thrown, recorder.calls().get(0).getError());
                    });
        }
    }

    @Test
    void ac_ext_45_in_loopback_mode_the_http_client_observation_is_a_child() throws Exception {
        Recorder recorder = new Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, recorder, "topolith.modules.account.mode=loopback").run(context -> {
                assertNull(context.getStartupFailure());
                context.getBean(Holder.class).account().find("ok");
                assertChild(recorder);
            });
        }
    }

    @Test
    void ac_ext_45_in_loopback_mode_the_child_holds_when_the_exchange_runs_on_another_thread() throws Exception {
        Recorder recorder = new Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, recorder, "topolith.modules.account.mode=loopback")
                    .withUserConfiguration(ContextPropagationTest.Hop.class).run(context -> {
                        assertNull(context.getStartupFailure());
                        context.getBean(Holder.class).account().find("ok");
                        assertChild(recorder);
                    });
        }
    }

    @Test
    void ac_ext_45_in_external_mode_the_child_holds_when_the_exchange_runs_on_another_thread() throws Exception {
        Recorder provider = new Recorder();
        Recorder recorder = new Recorder();
        serveExposed(provider, context -> external("http://127.0.0.1:" + ProviderErrorsTest.port(context), recorder,
                new Class<?>[] {ContextPropagationTest.Hop.class, Spy.class}, caller -> {
                    Spy.seen.clear();
                    caller.getBean(AccountContract.class).find("ok");
                    assertChild(recorder);
                    // a spy outside the handoff saw the captured observation on the request
                    assertEquals(List.of("account:true"), Spy.seen);
                }));
    }

    /** Records, per group, whether a request reaches the adapter carrying the captured observation. */
    @Configuration(proxyBeanMethods = false)
    static class Spy {
        static final List<String> seen = new CopyOnWriteArrayList<>();

        @Bean
        RestClientHttpServiceGroupConfigurer spy() {
            return groups -> groups.forEachProxyFactory((group, factory) -> factory.exchangeAdapterDecorator(
                    adapter -> (HttpExchangeAdapter) Proxy.newProxyInstance(
                            HttpExchangeAdapter.class.getClassLoader(), new Class<?>[] {HttpExchangeAdapter.class},
                            (proxy, method, args) -> {
                                if (args != null && args[0] instanceof HttpRequestValues values) {
                                    seen.add(group.name() + ":"
                                            + values.getAttributes().containsKey(ObservationHandoff.OBSERVATION));
                                }
                                try {
                                    return method.invoke(adapter, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            })));
        }
    }

    @Test
    void ac_ext_45_without_a_registry_no_observation_is_captured_and_the_call_succeeds() throws Exception {
        Recorder provider = new Recorder();
        serveExposed(provider, context -> {
            Spy.seen.clear();
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                runner(loader, ClientHost.class, null, NO_BOOT_REGISTRY, "topolith.modules.payment.mode=none",
                        "topolith.modules.account.mode=external",
                        "topolith.modules.account.remote.url=http://127.0.0.1:" + ProviderErrorsTest.port(context))
                        .withUserConfiguration(ContextPropagationTest.Hop.class, Spy.class).run(caller -> {
                            assertNull(caller.getStartupFailure());
                            assertTrue(caller.getBeansOfType(ObservationRegistry.class).isEmpty());
                            assertEquals("found", caller.getBean(AccountContract.class).find("ok"));
                            assertEquals(List.of("account:false"), Spy.seen);
                        });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void ac_ext_45_an_internal_or_none_module_gets_no_handoff_and_only_the_external_group_is_touched()
            throws Exception {
        Spy.seen.clear();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            runner(loader, ClientHost.class, new Recorder(), "topolith.modules.account.mode=internal",
                    "topolith.modules.payment.mode=none", "topolith.modules.boundary.mode=none")
                    .withUserConfiguration(Spy.class).run(context -> {
                        assertNull(context.getStartupFailure());
                        assertFalse(context.containsBean("topolithObservationHandoff"));
                        assertTrue(Spy.seen.isEmpty());
                    });
        }
        Recorder provider = new Recorder();
        serveExposed(provider, context -> {
            Spy.seen.clear();
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                runner(loader, ClientHost.class, new Recorder(), "topolith.modules.account.mode=external",
                        "topolith.modules.account.remote.url=http://127.0.0.1:" + ProviderErrorsTest.port(context),
                        "topolith.modules.payment.mode=none", "topolith.modules.boundary.mode=none")
                        .withUserConfiguration(Spy.class).run(caller -> {
                            assertNull(caller.getStartupFailure());
                            assertEquals("found", caller.getBean(AccountContract.class).find("ok"));
                            assertEquals(List.of("account:true"), Spy.seen);
                        });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void assertChild(Recorder recorder) {
        List<Observation.Context> http = recorder.named("http.client.requests");
        assertEquals(1, http.size(), "the HTTP client's observation: " + recorder.stopped);
        ObservationView parent = http.get(0).getParentObservation();
        assertTrue(parent != null && parent.getContextView() == recorder.calls().get(0), String.valueOf(parent));
    }

    /** An external caller host at {@code url}, with its registry. */
    private void external(String url, Recorder recorder, Consumer<AssertableApplicationContext> check,
            String... properties) throws Exception {
        external(url, recorder, new Class<?>[0], check, properties);
    }

    private void external(String url, Recorder recorder, Class<?>[] extra,
            Consumer<AssertableApplicationContext> check, String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            runner(loader, ClientHost.class, recorder, "topolith.modules.payment.mode=none",
                    "topolith.modules.account.mode=external", "topolith.modules.account.remote.url=" + url,
                    "topolith.modules.account.remote.read-timeout=2s").withUserConfiguration(extra)
                    .withPropertyValues(properties)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        check.accept(context);
                    });
        }
    }

    @Test
    void ac_ext_70_and_73_external_mode_records_one_observation() throws Exception {
        Recorder provider = new Recorder();
        Recorder recorder = new Recorder();
        serveExposed(provider, context -> external("http://127.0.0.1:" + ProviderErrorsTest.port(context), recorder,
                caller -> {
                    AccountContract account = caller.getBean(AccountContract.class);
                    assertMatrix(recorder, account, "external");
                    assertChecked(recorder, account, "external");
                    assertMethodSecurity(recorder, account, "external");
                    recorder.clear();
                    caller.getBean(AccountContract.class).find("ok");
                    assertChild(recorder);
                }));
    }

    @Test
    void ac_ext_44_a_refused_connection_sets_module_unavailable() throws Exception {
        Recorder recorder = new Recorder();
        external("http://127.0.0.1:1", recorder, context -> {
            assertThrows(ModuleUnavailableException.class, () -> context.getBean(AccountContract.class).find("ok"));
            assertEquals(1, recorder.calls().size());
            assertKeys(recorder.calls().get(0), "account", "external", "_host", "ModuleUnavailableException");
        });
    }

    private interface Check {
        void accept(AssertableWebApplicationContext context) throws Exception;
    }

    /** A host serving the exposed internal account and payment modules, with the registry of {@code recorder}. */
    private void serveExposed(Recorder recorder, Check check) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader)
                    .withUserConfiguration(ErrorHost.class, ProviderErrorsTest.MethodSecurity.class)
                    .withBean(ObservationRegistry.class, recorder::registry)
                    .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                            "topolith.modules.boundary.mode=none", "topolith.modules.account.mode=internal",
                            "topolith.modules.payment.mode=internal", "topolith.modules.account.expose.enabled=true")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        try {
                            check.accept(context);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
        }
    }

    @Test
    void ac_ext_42_an_exposed_module_serving_an_external_call_records_none_but_its_handler_calls() throws Exception {
        Recorder recorder = new Recorder();
        serveExposed(recorder, context -> {
            assertEquals(200, ProviderErrorsTest.get(context, "/account/find?case=ok").status());
            assertEquals(List.of(), recorder.calls());
            // a declared exception thrown by the handler is not observed on the provider either
            assertEquals(404, ProviderErrorsTest.get(context, "/account/find?case=missing").status());
            assertEquals(List.of(), recorder.calls());
            // the call the handler makes is observed, once, as the account module's call
            assertEquals(200, ProviderErrorsTest.get(context, "/account/find?case=pay").status());
            assertEquals(1, recorder.calls().size());
            assertEquals("payment", key(recorder.calls().get(0), "module.id"));
            assertEquals("account", key(recorder.calls().get(0), "caller.module"));
            // the mark is consumed: a host call of the same method on the same bean is observed
            context.getBean(AccountContract.class).find("ok");
            assertEquals(2, recorder.calls().size());
        });
    }

    @Test
    void ac_ext_46_without_a_registry_a_call_succeeds_and_a_none_module_gets_nothing() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            runner(loader, ClientHost.class, null, NO_BOOT_REGISTRY, "topolith.modules.account.mode=internal",
                    "topolith.modules.payment.mode=none").run(context -> {
                        assertNull(context.getStartupFailure());
                        assertTrue(context.getBeansOfType(ObservationRegistry.class).isEmpty());
                        assertEquals("found", context.getBean(AccountContract.class).find("ok"));
                    });
        }
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            runner(loader, ClientHost.class, new Recorder(), "topolith.modules.account.mode=none",
                    "topolith.modules.payment.mode=none", "topolith.modules.boundary.mode=none").run(context -> {
                        assertNull(context.getStartupFailure());
                        assertFalse(context.containsBean("topolithModuleCallObserver"));
                        assertFalse(context.containsBean("topolithContractBoundary"));
                        assertTrue(context.getBeansOfType(AccountContract.class).isEmpty());
                    });
        }
    }

    private static TopolithCode failure(AssertableApplicationContext context) {
        Throwable cause = context.getStartupFailure();
        while (cause != null && !(cause instanceof TopolithException)) {
            cause = cause.getCause();
        }
        assertInstanceOf(TopolithException.class, cause, String.valueOf(context.getStartupFailure()));
        return ((TopolithException) cause).diagnostics().get(0).code();
    }

    @Test
    void ac_ext_46_a_final_handler_provider_fails_with_neither_a_transaction_manager_nor_a_registry()
            throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(
                new FilteredClassLoader("org.springframework.transaction"), root)) {
            runner(loader, ErrorHost.class, null, NO_BOOT_REGISTRY, "topolith.modules.boundary.mode=internal")
                    .withUserConfiguration(ContractBoundaryTest.FinalProviderConfiguration.class).run(context -> {
                        assertEquals(TopolithCode.TL0029, failure(context));
                    });
        }
    }

    @Test
    void ac_ext_46_a_final_handler_provider_fails_while_observation_is_turned_off() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            // Boot's own registry applies its enable property
            runner(loader, ErrorHost.class, null, "topolith.modules.boundary.mode=internal",
                    "management.observations.enable.topolith=false")
                    .withUserConfiguration(ContractBoundaryTest.FinalProviderConfiguration.class).run(context -> {
                        assertEquals(TopolithCode.TL0029, failure(context));
                    });
        }
    }

    @Test
    void ac_ext_46_turning_observation_off_records_nothing_and_the_call_succeeds() throws Exception {
        for (boolean enabled : new boolean[] {true, false}) {
            Recorder recorder = new Recorder();
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                // Boot's registry picks up the handler bean and applies management.observations.enable.*
                runner(loader, ClientHost.class, null, "topolith.modules.account.mode=internal",
                        "management.observations.enable.topolith=" + enabled)
                        .withBean(ObservationHandler.class, () -> recorder).run(context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals("found", context.getBean(AccountContract.class).find("ok"));
                            assertEquals(enabled ? 1 : 0, recorder.calls().size());
                        });
            }
        }
    }

    /** Renames the observation and adds a key. */
    static class Custom extends DefaultModuleCallObservationConvention {
        @Override
        public String getName() {
            return "my.call";
        }

        @Override
        public KeyValues getLowCardinalityKeyValues(ModuleCallContext context) {
            return super.getLowCardinalityKeyValues(context).and("team", "blue");
        }
    }

    @Test
    void ac_ext_47_a_convention_bean_renames_and_adds_a_key_in_every_mode() throws Exception {
        Recorder recorder = new Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            runner(loader, ClientHost.class, recorder, "topolith.modules.account.mode=internal",
                    "topolith.modules.payment.mode=none").withBean(Custom.class).run(context -> {
                        assertNull(context.getStartupFailure());
                        context.getBean(AccountContract.class).find("ok");
                        assertCustom(recorder, "internal");
                    });
        }
        Recorder looped = new Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, looped, "topolith.modules.account.mode=loopback").withBean(Custom.class).run(context -> {
                assertNull(context.getStartupFailure());
                context.getBean(Holder.class).account().find("ok");
                assertCustom(looped, "loopback");
            });
        }
        Recorder provider = new Recorder();
        Recorder caller = new Recorder();
        serveExposed(provider, context -> {
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                runner(loader, ClientHost.class, caller, "topolith.modules.payment.mode=none",
                        "topolith.modules.account.mode=external",
                        "topolith.modules.account.remote.url=http://127.0.0.1:" + ProviderErrorsTest.port(context))
                        .withBean(Custom.class).run(external -> {
                                    assertNull(external.getStartupFailure());
                                    external.getBean(AccountContract.class).find("ok");
                                    assertCustom(caller, "external");
                                });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void assertCustom(Recorder recorder, String mode) {
        assertEquals(1, recorder.calls().size());
        ModuleCallContext call = recorder.calls().get(0);
        assertEquals("my.call", call.getName());
        assertEquals("blue", key(call, "team"));
        assertEquals(mode, key(call, "module.mode"));
        assertEquals(Mode.parse(mode).orElseThrow(), call.mode());
    }

    @Test
    void ac_ext_47_two_convention_beans_fail_startup_with_tl0033() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            runner(loader, ClientHost.class, new Recorder(), "topolith.modules.account.mode=internal",
                    "topolith.modules.payment.mode=none").withBean("first", Custom.class)
                    .withBean("second", Custom.class).run(context -> assertEquals(TopolithCode.TL0033,
                            failure(context)));
        }
    }

    @Test
    void ac_ext_46_with_spring_tx_reported_absent_the_call_is_observed_without_the_boundary() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(
                new FilteredClassLoader("org.springframework.transaction"), root)) {
            Recorder recorder = new Recorder();
            runner(loader, ErrorHost.class, recorder, "topolith.modules.boundary.mode=internal").run(context -> {
                assertNull(context.getStartupFailure());
                context.getBean(BoundaryContract.class).plain();
                assertEquals(1, recorder.calls().size());
            });
        }
    }
}
