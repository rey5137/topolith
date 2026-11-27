package io.github.rey5137.topolith.spring.cloud;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.internal.Outcomes;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceSettings;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.ReflectionUtils;

/**
 * The only class that names Resilience4j (D-105): it builds module {@code <id>}'s breaker from a fresh configuration,
 * so nothing of the host's default configuration, time limiter or bulkhead applies, and runs it on the calling thread.
 * Package-private, with no public SPI (Q-31).
 */
final class Resilience4jAdapter {

    private static final List<String> OTHER_SOURCES = List.of("resilience4j.circuitbreaker.instances",
            "resilience4j.circuitbreaker.configs", "resilience4j.timelimiter.instances",
            "resilience4j.timelimiter.configs", "spring.cloud.circuitbreaker.resilience4j.disable-time-limiter-map");

    private Resilience4jAdapter() {
    }

    /** The breaker of module {@code id}; TL0003 when anything else configures or owns breaker {@code id}. */
    static BreakerCall create(Object supported, String id, ResilienceSettings.Breaker settings,
            Environment environment, boolean replacing) {
        Resilience4JCircuitBreakerFactory factory = (Resilience4JCircuitBreakerFactory) supported;
        String conflict = conflict(factory, id, environment, replacing);
        if (conflict != null) {
            throw new TopolithException(new Diagnostic(TopolithCode.TL0003, "module '" + id
                    + "' sets topolith.modules." + id + ".remote.circuit-breaker.enabled=true, but " + conflict
                    + ", and Topolith alone owns circuit breaker '" + id + "'.", fix(conflict, id)));
        }
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.failureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .recordException(failure -> Outcomes.classify(failure).breakerFailure())
                .ignoreException(failure -> !Outcomes.classify(failure).breakerFailure()).build();
        CircuitBreakerRegistry registry = factory.getCircuitBreakerRegistry();
        // the call is bound to this instance, not to the registry name: a rebuild or removal cannot be undone by an
        // attempt in flight (D-107), and nothing re-creates <id> on a run
        CircuitBreaker built = CircuitBreaker.of(id, config, registry.getTags());
        if (replacing) {
            registry.replace(id, built);
        }
        // a first breaker (or one a host removed) is created by the registry, which then holds the instance bound here
        CircuitBreaker breaker = registry.find(id).orElse(null) == built ? built : registry.circuitBreaker(id, config);
        return new BreakerCall() {
            @Override
            public <T> T run(Supplier<T> attempt) {
                // no executor, no time limiter and no bulkhead: the attempt runs on the calling thread, and a
                // failure goes back as thrown: a rethrow is not a fallback value (INV-4)
                return breaker.decorateSupplier(attempt).get();
            }

            @Override
            public boolean registered() {
                return registry.find(id).orElse(null) == breaker;
            }
        };
    }

    /** Forgets module {@code id}'s breaker, so a later refresh to enabled finds the name free. */
    static void remove(Object supported, String id) {
        ((Resilience4JCircuitBreakerFactory) supported).getCircuitBreakerRegistry().remove(id);
    }

    private static String fix(String conflict, String id) {
        return conflict.contains("bulkhead") ? "Set spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled=false."
                : "Remove what configures circuit breaker '" + id + "', or set topolith.modules." + id
                        + ".remote.circuit-breaker.enabled=false.";
    }

    private static String conflict(Resilience4JCircuitBreakerFactory factory, String id, Environment environment,
            boolean replacing) {
        if (factory.getBulkheadProvider() != null) {
            return "the factory has an active Resilience4j bulkhead provider";
        }
        if (configured(factory, "getConfigurations", null, id)) {
            return "a Spring Cloud Customizer or configure callback configures '" + id + "'";
        }
        if (configured(factory, null, "circuitBreakerCustomizers", id)) {
            return "a Spring Cloud circuit breaker customizer is registered for '" + id + "'";
        }
        Binder binder = Binder.get(environment);
        for (String source : OTHER_SOURCES) {
            Map<String, Object> bound = binder.bind(source, Bindable.mapOf(String.class, Object.class))
                    .orElse(Map.of());
            if (bound.containsKey(id)) {
                return source + "." + id + " is set";
            }
        }
        if (!replacing && factory.getCircuitBreakerRegistry().find(id).isPresent()) {
            return "a circuit breaker named '" + id + "' is already in the CircuitBreakerRegistry";
        }
        return null;
    }

    /** Whether the factory's map, read by method or by field, has an entry for {@code id}. */
    private static boolean configured(Resilience4JCircuitBreakerFactory factory, String method, String field,
            String id) {
        try {
            Object map;
            if (method != null) {
                Method getter = ReflectionUtils.findMethod(factory.getClass(), method);
                ReflectionUtils.makeAccessible(getter);
                map = ReflectionUtils.invokeMethod(getter, factory);
            } else {
                Field found = ReflectionUtils.findField(factory.getClass(), field);
                ReflectionUtils.makeAccessible(found);
                map = ReflectionUtils.getField(found, factory);
            }
            return map instanceof Map<?, ?> entries && entries.containsKey(id);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
