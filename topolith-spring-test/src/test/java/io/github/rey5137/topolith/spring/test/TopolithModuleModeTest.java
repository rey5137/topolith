package io.github.rey5137.topolith.spring.test;

import fixture.payment.PaymentImpl;
import fixture.payment.api.Payment;
import io.github.rey5137.topolith.core.Mode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;

class TopolithModuleModeTest {
    private static ClassLoader original;
    private static java.net.URLClassLoader fixtures;

    @BeforeAll
    static void installFixtures() {
        original = Thread.currentThread().getContextClassLoader();
        fixtures = FixtureLoader.create();
        Thread.currentThread().setContextClassLoader(fixtures);
    }

    @AfterAll
    static void removeFixtures() throws java.io.IOException {
        Thread.currentThread().setContextClassLoader(original);
        fixtures.close();
    }

    @TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
    @TopolithModuleMode(id = "ledger", mode = Mode.NONE)
    static class TwoModes { }

    @TopolithModuleMode(id = "ledger", mode = Mode.NONE)
    @TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
    static class SameModes { }

    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    static class OtherModes { }

    @Test
    void ac_test_01_repeatable_modes_define_cache_key_and_close_server() throws Exception {
        var factory = new TopolithContextCustomizerFactory();
        var customizer = factory.createContextCustomizer(TwoModes.class, java.util.List.of());
        var same = factory.createContextCustomizer(SameModes.class, java.util.List.of());
        var other = factory.createContextCustomizer(OtherModes.class, java.util.List.of());
        assertEquals(customizer, same);
        assertEquals(customizer.hashCode(), same.hashCode());
        assertNotEquals(customizer, other);
        var context = new org.springframework.context.support.GenericApplicationContext();
        customizer.customizeContext(context, null);
        context.refresh();
        var server = context.getBean(TopolithContextCustomizerFactory.beanName("payment"), TopolithMockServer.class);
        assertEquals("none", context.getEnvironment().getProperty("topolith.modules.ledger.mode"));
        assertEquals(server.url().toString(),
                context.getEnvironment().getProperty("topolith.modules.payment.remote.url"));
        context.close();
        var request = java.net.http.HttpRequest.newBuilder(server.url())
                .timeout(java.time.Duration.ofSeconds(2)).build();
        assertThrows(java.io.IOException.class, () -> java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.discarding()));
    }

    @TopolithModuleMode(id = "payment", mode = Mode.INTERNAL)
    static class BaseMode { }

    @TopolithModuleMode(id = "ledger", mode = Mode.NONE)
    static class AdditionalMode extends BaseMode { }

    @TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
    @TopolithModuleMode(id = "ledger", mode = Mode.NONE)
    static class BaseTwoModes { }

    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    static class OverrideMode extends BaseTwoModes { }

    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    @TopolithModuleMode(id = "payment", mode = Mode.INTERNAL)
    static class DuplicateModes { }

    @Test
    void ac_test_01_inherited_modes_combine_and_nearest_override_wins() {
        var factory = new TopolithContextCustomizerFactory();
        var inherited = factory.createContextCustomizer(AdditionalMode.class, java.util.List.of());
        var overridden = factory.createContextCustomizer(OverrideMode.class, java.util.List.of());
        var context = new org.springframework.context.support.GenericApplicationContext();
        inherited.customizeContext(context, null);
        assertEquals("internal", context.getEnvironment().getProperty("topolith.modules.payment.mode"));
        assertEquals("none", context.getEnvironment().getProperty("topolith.modules.ledger.mode"));
        var overriddenContext = new org.springframework.context.support.GenericApplicationContext();
        overridden.customizeContext(overriddenContext, null);
        assertEquals("none", overriddenContext.getEnvironment().getProperty("topolith.modules.payment.mode"));
        assertEquals("none", overriddenContext.getEnvironment().getProperty("topolith.modules.ledger.mode"));
        assertNotEquals(inherited, overridden);
        assertThrows(IllegalArgumentException.class,
                () -> factory.createContextCustomizer(DuplicateModes.class, java.util.List.of()));
    }

    @Nested
    @TopolithModuleMode(id = "payment", mode = Mode.INTERNAL)
    class OuterInternal {
        @Nested
        @SpringBootTest(classes = TopolithTestModesTest.Host.class)
        class Inner {
            @Autowired Payment payment;
            @Test
            void ac_test_01_nested_inherits_outer_mode() {
                assertEquals("internal", payment.ping());
            }
        }
    }

    @Nested
    @TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
    class OuterExternal {
        @TopolithMock(id = "payment") TopolithMockServer server;
        @Nested
        @SpringBootTest(classes = TopolithTestModesTest.Host.class)
        class Inner {
            @Autowired Payment payment;
            @Test
            void ac_test_01_nested_injects_outer_mock() {
                assertNotNull(server);
                server.stub("GET", "/ping", 200, "outer");
                assertEquals("outer", payment.ping());
            }
        }
    }

    @Nested
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
    class External {
        @Autowired Payment client;
        @Autowired Environment environment;
        @TopolithMock(id = "payment") TopolithMockServer server;

        @Test
        void ac_test_01_annotation_selects_external() {
            assertEquals("external", environment.getProperty("topolith.modules.payment.mode"));
            assertNotNull(server);
        }

        @Test
        void ac_test_03_annotation_client_reaches_stub() {
            server.reset();
            server.stub("GET", "/ping", 200, "pong");
            assertEquals("pong", client.ping());
            assertTrue(server.requests().stream().anyMatch(request -> request.path().equals("/payment/ping")));
        }
    }

    @Nested
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @TopolithModuleMode(id = "payment", mode = Mode.INTERNAL)
    class Internal {
        @Autowired ApplicationContext context;
        @Test
        void ac_test_01_annotation_selects_internal() {
            assertEquals("internal", context.getBean(Payment.class).ping());
            assertNotNull(context.getBean(PaymentImpl.class));
            assertNotNull(context.getBean(fixture.payment.PaymentRoute.class));
            assertNotNull(context.getBean(fixture.payment.PaymentAutoConfiguration.PaymentMarker.class));
        }
    }

    @Nested
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    class None {
        @Autowired ApplicationContext context;
        @Test
        void ac_test_02_annotation_none_has_no_contributions() {
            assertEquals("none", context.getEnvironment().getProperty("topolith.modules.payment.mode"));
            assertTrue(context.getBeansOfType(Payment.class).isEmpty());
            assertTrue(context.getBeansOfType(PaymentImpl.class).isEmpty());
            assertTrue(context.getBeansOfType(fixture.payment.PaymentRoute.class).isEmpty());
            assertTrue(context.getBeansOfType(fixture.payment.PaymentAutoConfiguration.class).isEmpty());
            assertTrue(context.getBeansOfType(fixture.payment.PaymentAutoConfiguration.PaymentMarker.class).isEmpty());
        }
    }

    @Nested
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
    @org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
    class ResetBetweenMethods {
        @Autowired Payment client;
        @TopolithMock(id = "payment") TopolithMockServer server;

        @Test
        @org.junit.jupiter.api.Order(1)
        void ac_test_03_first_method_stubs_and_records() {
            server.stub("GET", "/ping", 200, "pong");
            assertEquals("pong", client.ping());
            assertEquals(1, server.requests().size());
        }

        @Test
        @org.junit.jupiter.api.Order(2)
        void ac_test_03_stubs_and_requests_reset_after_each_method() throws Exception {
            assertTrue(server.requests().isEmpty());
            var request = java.net.http.HttpRequest.newBuilder(server.url().resolve("/payment/ping")).build();
            var response = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode());
        }

        @Test
        @org.junit.jupiter.api.Order(3)
        void ac_test_03_late_version_check_leaves_the_recorded_count_unchanged() throws Exception {
            server.stub("GET", "/ping", 200, "pong");
            assertEquals("pong", client.ping());
            var late = java.net.http.HttpRequest.newBuilder(server.url().resolve("/payment/.topolith/api-version"))
                    .build();
            java.net.http.HttpClient.newHttpClient().send(late, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(1, server.requests().size());
            assertEquals("/payment/ping", server.requests().get(0).path());
        }
    }

    @Nested
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
    class VersionCheckDrift {
        @Autowired org.springframework.beans.factory.BeanFactory beanFactory;
        @TopolithMock(id = "payment") TopolithMockServer server;

        /** Boot's check must send the path the mock excludes; if boot's path changes this fails loudly. */
        @Test
        void ac_test_03_boot_version_check_reaches_the_mock_path_unrecorded() throws Exception {
            server.stub("GET", TopolithMockServer.VERSION_PATH, 200, "2.0.0");
            Class<?> resolver = Class.forName("io.github.rey5137.topolith.spring.boot.TopologyResolver");
            String beanName = (String) fieldValue(resolver, "RESOLUTION_BEAN");
            Object resolution = beanFactory.getBean(beanName);
            java.lang.reflect.Method check = null;
            for (var method : Class.forName("io.github.rey5137.topolith.spring.boot.ExternalVersionCheck")
                    .getDeclaredMethods()) {
                if (method.getName().equals("check") && method.getParameterCount() == 3) { check = method; }
            }
            assertNotNull(check);
            check.setAccessible(true);
            java.util.List<String> warnings = new java.util.ArrayList<>();
            check.invoke(null, beanFactory, resolution, (java.util.function.Consumer<String>) warnings::add);
            assertEquals(1, warnings.size(), warnings::toString);
            assertTrue(warnings.get(0).contains("TL0023"), warnings.get(0));
            assertTrue(server.requests().isEmpty());
        }

        private static Object fieldValue(Class<?> type, String name) throws Exception {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        }
    }

    @Test
    void ac_test_01_package_private_spi_classes_load_from_spring_factories() {
        var loader = org.springframework.core.io.support.SpringFactoriesLoader.forDefaultResourceLocation(
                getClass().getClassLoader());
        // other modules' factories may lack optional classes here; only ours is under test
        org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler ignoreOthers =
                (type, name, failure) -> { };
        assertTrue(loader.load(org.springframework.test.context.ContextCustomizerFactory.class, ignoreOthers).stream()
                .anyMatch(f -> f instanceof TopolithContextCustomizerFactory));
        assertTrue(loader.load(org.springframework.test.context.TestExecutionListener.class, ignoreOthers).stream()
                .anyMatch(f -> f instanceof TopolithMockTestExecutionListener));
    }

    @Nested
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @org.springframework.test.context.ActiveProfiles("svc")
    @TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
    class ExternalWithHostServiceId {
        @Autowired Payment client;
        @Autowired Environment environment;
        @TopolithMock(id = "payment") TopolithMockServer server;

        @Test
        void ac_test_03_external_overrides_host_service_id_and_reaches_mock() {
            assertEquals("", environment.getProperty("topolith.modules.payment.remote.service-id"));
            server.stub("GET", "/ping", 200, "pong");
            assertEquals("pong", client.ping());
        }
    }

    @Nested
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @org.springframework.test.context.ActiveProfiles("svc")
    @TopolithModuleMode(id = "payment", mode = Mode.INTERNAL)
    class InternalWithHostServiceId {
        @Autowired Payment payment;
        @Test
        void ac_test_01_internal_ignores_host_service_id() { assertEquals("internal", payment.ping()); }
    }

    @Nested
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @org.springframework.test.context.ActiveProfiles("svc")
    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    class NoneWithHostServiceId {
        @Autowired ApplicationContext context;
        @Test
        void ac_test_02_none_ignores_host_service_id() {
            assertTrue(context.getBeansOfType(Payment.class).isEmpty());
        }
    }

    @Nested
    @SpringBootTest(classes = TopolithTestModesTest.Host.class, properties = "topolith.modules.payment.mode=none")
    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    class SameValuedTestProperty {
        @Autowired ApplicationContext context;
        @Test
        void ac_test_02_same_valued_test_property_passes() {
            assertTrue(context.getBeansOfType(Payment.class).isEmpty());
        }
    }

    @Nested
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    class SameValuedDynamicProperty {
        @org.springframework.test.context.DynamicPropertySource
        static void modes(org.springframework.test.context.DynamicPropertyRegistry registry) {
            TopolithTestModes.module("payment").none().applyTo(registry);
        }

        @Autowired ApplicationContext context;
        @Test
        void ac_test_02_same_valued_dynamic_property_passes() {
            assertTrue(context.getBeansOfType(Payment.class).isEmpty());
        }
    }

    @SpringBootTest(classes = TopolithTestModesTest.Host.class, properties = "topolith.modules.payment.mode=internal")
    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    static class InlineConflict { }

    @SpringBootTest(classes = TopolithTestModesTest.Host.class,
            properties = "topolith.modules.payment.remote.url=http://localhost:1")
    @TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
    static class UrlConflict { }

    @SpringBootTest(classes = TopolithTestModesTest.Host.class,
            properties = "topolith.modules.payment.remote.service-id=other-svc")
    @TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
    static class ServiceIdConflict { }

    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    static class ConflictBase { }

    @SpringBootTest(classes = TopolithTestModesTest.Host.class, properties = "topolith.modules.payment.mode=internal")
    static class SubclassConflict extends ConflictBase { }

    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    static class DynamicConflict {
        @org.springframework.test.context.DynamicPropertySource
        static void modes(org.springframework.test.context.DynamicPropertyRegistry registry) {
            TopolithTestModes.module("payment").internal().applyTo(registry);
        }
    }

    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
    static class DynamicUrlConflict {
        @org.springframework.test.context.DynamicPropertySource
        static void modes(org.springframework.test.context.DynamicPropertyRegistry registry) {
            TopolithTestModes.module("payment").external(java.net.URI.create("http://localhost:1")).applyTo(registry);
        }
    }

    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    class NestedOuter {
        @SpringBootTest(classes = TopolithTestModesTest.Host.class,
                properties = "topolith.modules.payment.mode=internal")
        class Inner { }
    }

    @org.springframework.test.context.TestPropertySource(locations = "classpath:${topolith.test.conflict}.properties")
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    static class PlaceholderLocationConflict { }

    static final class ConflictFactory implements org.springframework.core.io.support.PropertySourceFactory {
        @Override
        public org.springframework.core.env.PropertySource<?> createPropertySource(String name,
                org.springframework.core.io.support.EncodedResource resource) {
            return new org.springframework.core.env.MapPropertySource("odd-name",
                    java.util.Map.of("topolith.modules.payment.mode", "internal"));
        }
    }

    @org.springframework.test.context.TestPropertySource(locations = "classpath:topolith-conflict-internal.properties",
            factory = ConflictFactory.class)
    @SpringBootTest(classes = TopolithTestModesTest.Host.class)
    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    static class FactoryConflict { }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class RegistrarConfig {
        @org.springframework.context.annotation.Bean
        static org.springframework.test.context.DynamicPropertyRegistrar modes() {
            return registry -> registry.add("topolith.modules.payment.mode", () -> "internal");
        }
    }

    @SpringBootTest(classes = {TopolithTestModesTest.Host.class, RegistrarConfig.class})
    @TopolithModuleMode(id = "payment", mode = Mode.NONE)
    static class RegistrarConflict { }

    @Test
    void ac_test_01_placeholder_location_conflict_fails() {
        System.setProperty("topolith.test.conflict", "topolith-conflict-internal");
        try {
            assertConflict(PlaceholderLocationConflict.class, "='none'", "sets 'internal'");
        } finally {
            System.clearProperty("topolith.test.conflict");
        }
    }

    @Test
    void ac_test_01_custom_factory_source_conflict_fails() {
        assertConflict(FactoryConflict.class, "='none'", "sets 'internal'", "odd-name");
    }

    @Test
    void ac_test_01_dynamic_property_registrar_conflict_fails() {
        assertConflict(RegistrarConflict.class, "='none'", "sets 'internal'");
    }

    static boolean lazyCreated;

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class LazyConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Lazy
        static TopolithMockServer lazyServer() {
            lazyCreated = true;
            return new TopolithMockServer("ledger");
        }
    }

    @SpringBootTest(classes = {TopolithTestModesTest.Host.class, LazyConfig.class})
    @TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
    static class LazyHost { }

    @Test
    void ac_test_03_listener_reset_does_not_create_lazy_beans() {
        lazyCreated = false;
        var manager = new org.springframework.test.context.TestContextManager(LazyHost.class);
        var context = manager.getTestContext();
        var listener = new TopolithMockTestExecutionListener();
        var server = context.getApplicationContext().getBean(TopolithContextCustomizerFactory.beanName("payment"),
                TopolithMockServer.class);
        server.stub("GET", "/x", 200, "x");
        listener.beforeTestMethod(context);
        listener.afterTestMethod(context);
        assertFalse(lazyCreated, "the listener created a lazy bean");
        var request = java.net.http.HttpRequest.newBuilder(server.url().resolve("/payment/x")).build();
        assertDoesNotThrow(() -> assertEquals(404, java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()));
        ((org.springframework.context.ConfigurableApplicationContext) context.getApplicationContext()).close();
    }

    /** Loads the context the way the test framework would and returns the failure, if any. */
    private static String failure(Class<?> testClass) {
        try {
            var context = new org.springframework.test.context.TestContextManager(testClass).getTestContext()
                    .getApplicationContext();
            ((org.springframework.context.ConfigurableApplicationContext) context).close();
            return null;
        } catch (Throwable e) {
            var messages = new StringBuilder();
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                messages.append(cause.getMessage()).append('\n');
            }
            return messages.toString();
        }
    }

    private static void assertConflict(Class<?> testClass, String... expected) {
        String failure = failure(testClass);
        assertNotNull(failure, "context loaded");
        assertTrue(failure.contains("Module 'payment'"), failure);
        for (String part : expected) { assertTrue(failure.contains(part), part + " in " + failure); }
    }

    @Test
    void ac_test_01_conflicting_inline_mode_fails_naming_both_values() {
        assertConflict(InlineConflict.class, "='none'", "sets 'internal'", "Inlined Test Properties");
    }

    @Test
    void ac_test_01_conflicting_remote_url_fails() {
        assertConflict(UrlConflict.class, "remote.url", "sets 'http://localhost:1'");
    }

    @Test
    void ac_test_01_conflicting_service_id_fails() {
        assertConflict(ServiceIdConflict.class, "remote.service-id", "sets 'other-svc'");
    }

    @Test
    void ac_test_01_conflict_with_superclass_annotation_fails() {
        assertConflict(SubclassConflict.class, "='none'", "sets 'internal'");
    }

    @Test
    void ac_test_01_conflict_with_enclosing_annotation_fails() {
        assertConflict(NestedOuter.Inner.class, "='none'", "sets 'internal'");
    }

    @Test
    void ac_test_01_conflicting_dynamic_property_fails() {
        assertConflict(DynamicConflict.class, "='none'", "sets 'internal'", "Dynamic Test Properties");
    }

    @Test
    void ac_test_01_dynamic_url_of_another_server_fails() {
        assertConflict(DynamicUrlConflict.class, "remote.url", "sets 'http://localhost:1'");
    }
}
