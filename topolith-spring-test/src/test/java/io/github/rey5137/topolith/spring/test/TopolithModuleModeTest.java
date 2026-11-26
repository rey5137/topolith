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
        @TopolithMock("payment") TopolithMockServer server;
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
        @TopolithMock("payment") TopolithMockServer server;

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
}
