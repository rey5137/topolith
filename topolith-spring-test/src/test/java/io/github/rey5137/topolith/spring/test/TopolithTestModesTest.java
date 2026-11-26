package io.github.rey5137.topolith.spring.test;

import fixture.payment.api.Payment;
import fixture.payment.api.PaymentException;
import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class TopolithTestModesTest {
    private static java.net.URLClassLoader fixtures;

    @org.junit.jupiter.api.BeforeAll
    static void installFixtures() { fixtures = FixtureLoader.create(); }

    @org.junit.jupiter.api.AfterAll
    static void removeFixtures() throws java.io.IOException { fixtures.close(); }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withClassLoader(fixtures).withUserConfiguration(Host.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Host { }

    @Test
    void ac_test_01_programmatic_modes_and_dynamic_registry() {
        var values = new java.util.HashMap<String, java.util.function.Supplier<Object>>();
        TopolithTestModes.module("a").internal().module("b").external(java.net.URI.create("http://localhost:42"))
                .module("c").none().applyTo((key, value) -> values.put(key, value));
        assertEquals("internal", values.get("topolith.modules.a.mode").get());
        assertEquals("external", values.get("topolith.modules.b.mode").get());
        assertEquals("http://localhost:42", values.get("topolith.modules.b.remote.url").get());
        assertEquals("none", values.get("topolith.modules.c.mode").get());
        assertFalse(values.containsKey("topolith.modules.d.mode"));
        TopolithTestModes.module("payment").internal()
                .applyTo(runner()).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("internal", context.getBean(Payment.class).ping());
                    assertNotNull(context.getBean(fixture.payment.PaymentRoute.class));
                    assertNotNull(context.getBean(fixture.payment.PaymentAutoConfiguration.PaymentMarker.class));
                });
    }

    @Test
    void ac_test_02_programmatic_none_has_no_contributions() {
        TopolithTestModes.module("payment").none()
                .applyTo(runner()).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(Payment.class).isEmpty());
                    assertTrue(context.getBeansOfType(fixture.payment.PaymentImpl.class).isEmpty());
                    assertTrue(context.getBeansOfType(fixture.payment.PaymentRoute.class).isEmpty());
                    assertTrue(context.getBeansOfType(fixture.payment.PaymentAutoConfiguration.class).isEmpty());
                    assertTrue(context.getBeansOfType(
                            fixture.payment.PaymentAutoConfiguration.PaymentMarker.class).isEmpty());
                });
    }

    @Test
    void ac_test_03_programmatic_client_reaches_stub() {
        try (var server = new TopolithMockServer("payment")) {
            server.stub("GET", "/ping", 200, "pong");
            TopolithTestModes.module("payment").external(server)
                    .applyTo(runner()).run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals("pong", context.getBean(Payment.class).ping());
                        assertTrue(server.requests().stream()
                                .anyMatch(request -> request.path().equals("/payment/ping")));
                    });
        }
    }

    @Test
    void ac_test_04_programmatic_client_maps_problems_and_outages() {
        try (var server = new TopolithMockServer("payment")) {
            var modes = TopolithTestModes.module("payment").external(server);
            modes.applyTo(runner()).run(context -> {
                assertNull(context.getStartupFailure());
                Payment client = context.getBean(Payment.class);
                server.problem("GET", "/ping", 400, PaymentException.class, "declined");
                assertEquals("declined", assertThrows(PaymentException.class, client::ping).getMessage());
                server.stub("GET", "/ping", 503, "{}");
                assertThrows(ModuleUnavailableException.class, client::ping);
                server.reset();
                RuntimeException missing = assertThrows(RuntimeException.class, client::ping);
                assertFalse(missing instanceof ModuleUnavailableException);
                assertFalse(missing instanceof PaymentException);
            });
        }
    }

    @Test
    void ac_test_03_builder_external_clears_host_service_id() {
        try (var server = new TopolithMockServer("payment")) {
            server.stub("GET", "/ping", 200, "pong");
            var host = runner().withPropertyValues("topolith.modules.payment.remote.service-id=payment-svc");
            TopolithTestModes.module("payment").external(server).applyTo(host).run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals("pong", context.getBean(Payment.class).ping());
            });
        }
    }

    @Test
    void ac_test_01_builder_mode_replaces_earlier_selection_of_the_module() {
        var values = new java.util.HashMap<String, java.util.function.Supplier<Object>>();
        TopolithTestModes.module("b").external(java.net.URI.create("http://localhost:42")).none()
                .applyTo((key, value) -> values.put(key, value));
        assertEquals(java.util.Set.of("topolith.modules.b.mode"), values.keySet());
    }

    @org.junit.jupiter.api.Test
    void ac_test_01_module_without_mode_fails_in_apply_to() {
        var builder = TopolithTestModes.module("payment").internal().module("ledger");
        var e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> builder.applyTo(new org.springframework.boot.test.context.runner.ApplicationContextRunner()));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("ledger"));
        org.junit.jupiter.api.Assertions.assertFalse(e.getMessage().contains("payment"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> builder.applyTo((org.springframework.test.context.DynamicPropertyRegistry) null));
    }

    @org.junit.jupiter.api.Test
    void ac_test_01_reselecting_a_configured_module_leaves_it_set() {
        var values = new java.util.HashMap<String, java.util.function.Supplier<Object>>();
        TopolithTestModes.module("a").internal().module("b").none().module("a")
                .applyTo((key, value) -> values.put(key, value));
        assertEquals("internal", values.get("topolith.modules.a.mode").get());
        assertEquals("none", values.get("topolith.modules.b.mode").get());
    }
}
