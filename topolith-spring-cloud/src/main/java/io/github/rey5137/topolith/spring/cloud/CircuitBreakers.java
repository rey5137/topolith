package io.github.rey5137.topolith.spring.cloud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceModules;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.httpservice.HttpServiceFallback;
import org.springframework.core.env.Environment;

/**
 * Finds the one supported {@link CircuitBreakerFactory} and builds the breaker of each module that enables one
 * (R-EXT-15, D-105). Loaded only with Spring Cloud CircuitBreaker's classes.
 */
final class CircuitBreakers {
    private static final String SUPPORTED =
            "org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory";

    private CircuitBreakers() {
    }

    /** TL0003 for a host {@code @HttpServiceFallback} that targets a module group, which would hide a failure. */
    static void checkFallbacks(ListableBeanFactory beans, ResilienceModules modules) {
        List<Diagnostic> problems = new ArrayList<>();
        List<String> names = new ArrayList<>(List.of(beans.getBeanNamesForAnnotation(HttpServiceFallback.class)));
        names.addAll(List.of(beans.getBeanNamesForAnnotation(HttpServiceFallback.Container.class)));
        for (String name : names.stream().distinct().toList()) {
            for (HttpServiceFallback fallback : beans.findAllAnnotationsOnBean(name, HttpServiceFallback.class,
                    false)) {
                for (String id : modules.ids()) {
                    boolean service = false;
                    for (Class<?> type : fallback.service()) {
                        service |= modules.module(id).services().contains(type.getName());
                    }
                    boolean everyGroup = fallback.group().isEmpty() && fallback.service().length == 0;
                    if (everyGroup || fallback.group().equals(id) || service) {
                        problems.add(new Diagnostic(TopolithCode.TL0003, "bean '" + name
                                + "' declares an @HttpServiceFallback for the group of module '" + id
                                + "', and Topolith installs no circuit-breaker fallback.", "Remove the fallback "
                                + "for module '" + id + "', so a failure is never hidden."));
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new TopolithException(problems);
        }
    }

    /**
     * The breaker of each module in {@code enabled}; TL0041 unless there is exactly one supported factory bean, TL0003
     * for what the adapter finds in the way.
     */
    static Map<String, BreakerCall> create(ListableBeanFactory beans, Environment environment,
            Map<String, ResilienceModules.Module> enabled) {
        Map<String, CircuitBreakerFactory> found = new TreeMap<>(beans.getBeansOfType(CircuitBreakerFactory.class));
        if (found.size() != 1 || !found.values().iterator().next().getClass().getName().equals(SUPPORTED)) {
            throw TopolithResilienceAutoConfiguration.factoryProblem(enabled.keySet(), found);
        }
        CircuitBreakerFactory<?, ?> factory = found.values().iterator().next();
        Map<String, BreakerCall> breakers = new TreeMap<>();
        enabled.forEach((id, module) -> breakers.put(id, Resilience4jAdapter.create(factory, id, module.breaker(),
                environment)));
        return breakers;
    }
}
