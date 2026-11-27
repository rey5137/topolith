package io.github.rey5137.topolith.spring.cloud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.TopolithAutoConfiguration;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceModules;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/**
 * Per-group retry and circuit breaker for the external and loopback modules that ask for them (R-EXT-14, R-EXT-15,
 * D-101, D-102). Not gated on Spring Cloud's context classes (D-20): a refresh is not needed to start. Without an
 * external or loopback module there is no {@code ResilienceModules} bean, so a {@code none} or {@code internal}
 * module leaves no trace (INV-2).
 */
@Incubating
@AutoConfiguration(after = TopolithAutoConfiguration.class)
@ConditionalOnClass(RestClientHttpServiceGroupConfigurer.class)
@ConditionalOnBean(ResilienceModules.class)
public class TopolithResilienceAutoConfiguration {
    private static final String COMMONS = "org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory";

    /**
     * Ordered after Topolith's observation handoff (10) and before its call translator, which has no order: the retry
     * advice added here is innermost, so the translator sees the final failure and the observation spans every
     * attempt (R-EXT-11).
     */
    @Bean
    @Order(20)
    RestClientHttpServiceGroupConfigurer topolithResilience(ResilienceModules modules, Environment environment,
            ConfigurableListableBeanFactory beans) {
        boolean commons = ClassUtils.isPresent(COMMONS, beans.getBeanClassLoader());
        if (commons) {
            CircuitBreakers.checkFallbacks(beans, modules);
        }
        Map<String, ResilienceModules.Module> enabled = new TreeMap<>();
        modules.ids().forEach(id -> {
            if (modules.module(id).breaker().enabled()) {
                enabled.put(id, modules.module(id));
            }
        });
        Map<String, BreakerCall> breakers = Map.of();
        if (!enabled.isEmpty()) {
            if (!commons) {
                throw factoryProblem(enabled.keySet(), Map.of());
            }
            breakers = CircuitBreakers.create(beans, environment, enabled);
        }
        Map<String, BreakerCall> installed = breakers;
        String[] names = modules.ids().stream().filter(id -> installed.containsKey(id)
                || modules.module(id).retry().active()).toArray(String[]::new);
        return groups -> {
            if (names.length > 0) {
                groups.filterByName(names).forEachProxyFactory((group, factory) -> {
                    BreakerCall breaker = installed.get(group.name());
                    if (breaker != null) {
                        factory.exchangeAdapterDecorator(adapter -> new BreakerAdapter(adapter, breaker));
                    }
                    if (modules.module(group.name()).retry().active()) {
                        factory.proxyFactoryCustomizer((proxy, service) -> proxy.addAdvice(0,
                                new RetryAdvice(group.name(), service, modules)));
                    }
                });
            }
        };
    }

    /** TL0041 for each enabled module: the factory beans found are not exactly one supported one. */
    static TopolithException factoryProblem(Iterable<String> ids, Map<String, ?> found) {
        List<Diagnostic> problems = new ArrayList<>();
        Map<String, String> types = new TreeMap<>();
        found.forEach((name, bean) -> types.put(name, bean.getClass().getName()));
        for (String id : ids) {
            problems.add(new Diagnostic(TopolithCode.TL0041, "module '" + id + "' sets topolith.modules." + id
                    + ".remote.circuit-breaker.enabled=true, but the CircuitBreakerFactory beans are " + types
                    + ", not exactly one Resilience4JCircuitBreakerFactory.", "Add a single supported factory such as "
                    + "spring-cloud-starter-circuitbreaker-resilience4j, or set topolith.modules." + id
                    + ".remote.circuit-breaker.enabled=false."));
        }
        return new TopolithException(problems);
    }
}
