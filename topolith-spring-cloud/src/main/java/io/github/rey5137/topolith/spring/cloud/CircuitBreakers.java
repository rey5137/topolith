package io.github.rey5137.topolith.spring.cloud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceModules;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceSettings;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.httpservice.CircuitBreakerRestClientHttpServiceGroupConfigurer;
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

    /** Whether Spring Cloud's global HTTP-service breaker configurer is in the context, whatever the key (D-106). */
    static boolean globalBreaker(ListableBeanFactory beans) {
        return beans.getBeanNamesForType(CircuitBreakerRestClientHttpServiceGroupConfigurer.class, true, false)
                .length > 0;
    }

    /** TL0003 for a resilient module that meets Spring Cloud's global breaker, which would wrap its group (D-106). */
    static void checkGlobal(Iterable<String> resilient) {
        List<Diagnostic> problems = new ArrayList<>();
        for (String id : resilient) {
            problems.add(new Diagnostic(TopolithCode.TL0003, "module '" + id + "' sets retry or a circuit breaker, "
                    + "but Spring Cloud's global HTTP-service circuit breaker is applied to its group "
                    + "(a CircuitBreakerRestClientHttpServiceGroupConfigurer bean is in the context).",
                    "Set spring.cloud.circuitbreaker.http-services.enabled=false, remove the bean that adds it, "
                    + "or turn retry and the breaker off for module '" + id + "'."));
        }
        if (!problems.isEmpty()) {
            throw new TopolithException(problems);
        }
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
     * for what the adapter finds in the way. {@code replacing} is a refresh: the module's own earlier breaker is not
     * in the way, and is dropped once the new one is built.
     */
    static Map<String, BreakerCall> create(ListableBeanFactory beans, Environment environment,
            Map<String, ResilienceSettings.Breaker> enabled, boolean replacing) {
        Map<String, CircuitBreakerFactory> found = new TreeMap<>(beans.getBeansOfType(CircuitBreakerFactory.class));
        if (found.size() != 1 || !found.values().iterator().next().getClass().getName().equals(SUPPORTED)) {
            throw TopolithResilienceAutoConfiguration.factoryProblem(enabled.keySet(), found);
        }
        CircuitBreakerFactory<?, ?> factory = found.values().iterator().next();
        Map<String, BreakerCall> breakers = new TreeMap<>();
        enabled.forEach((id, settings) -> breakers.put(id, Resilience4jAdapter.create(factory, id, settings,
                environment, replacing)));
        return breakers;
    }

    /** Drops module {@code id}'s breaker from the registry once the group stops using it (a refresh to disabled). */
    static void remove(ListableBeanFactory beans, String id) {
        beans.getBeansOfType(CircuitBreakerFactory.class).values().stream()
                .filter(factory -> factory.getClass().getName().equals(SUPPORTED))
                .forEach(factory -> Resilience4jAdapter.remove(factory, id));
    }
}
