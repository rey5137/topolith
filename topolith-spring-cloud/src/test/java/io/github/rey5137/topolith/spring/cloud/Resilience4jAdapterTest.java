package io.github.rey5137.topolith.spring.cloud;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Resilience4jAdapterTest {

    private static Resilience4JCircuitBreakerFactory factory() {
        return new Resilience4JCircuitBreakerFactory(CircuitBreakerRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(), null);
    }

    @Test
    void ac_ext_67_both_reflective_members_resolve_on_the_supported_version_and_an_empty_map_is_not_configured() {
        Resilience4JCircuitBreakerFactory factory = factory();
        assertFalse(Resilience4jAdapter.configured(factory, Resilience4jAdapter.CONFIGURATIONS, null, "orders"));
        assertFalse(Resilience4jAdapter.configured(factory, null, Resilience4jAdapter.CUSTOMIZERS, "orders"));
    }

    @Test
    void ac_ext_67_a_configured_id_is_found_by_both_members() {
        Resilience4JCircuitBreakerFactory factory = factory();
        factory.configure(builder -> builder.circuitBreakerConfig(
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.ofDefaults()), "orders");
        factory.addCircuitBreakerCustomizer(breaker -> { }, "ledger");
        assertTrue(Resilience4jAdapter.configured(factory, Resilience4jAdapter.CONFIGURATIONS, null, "orders"));
        assertTrue(Resilience4jAdapter.configured(factory, null, Resilience4jAdapter.CUSTOMIZERS, "ledger"));
        assertFalse(Resilience4jAdapter.configured(factory, null, Resilience4jAdapter.CUSTOMIZERS, "orders"));
    }

    @Test
    void ac_ext_67_a_missing_member_is_tl0041_not_not_configured() {
        Resilience4JCircuitBreakerFactory factory = factory();
        TopolithException byMethod = assertThrows(TopolithException.class,
                () -> Resilience4jAdapter.configured(factory, "noSuchGetter", null, "orders"));
        TopolithException byField = assertThrows(TopolithException.class,
                () -> Resilience4jAdapter.configured(factory, null, "noSuchField", "orders"));
        for (TopolithException failure : new TopolithException[] {byMethod, byField}) {
            assertEquals(TopolithCode.TL0041, failure.diagnostics().get(0).code());
            assertTrue(failure.getMessage().contains("circuit breaker 'orders'"), failure.getMessage());
        }
    }

    @Test
    void ac_ext_67_a_member_that_is_not_a_map_is_tl0041() {
        // getCircuitBreakerRegistry() is readable but is not a Map
        TopolithException failure = assertThrows(TopolithException.class,
                () -> Resilience4jAdapter.configured(factory(), "getCircuitBreakerRegistry", null, "orders"));
        assertEquals(TopolithCode.TL0041, failure.diagnostics().get(0).code());
    }
}
