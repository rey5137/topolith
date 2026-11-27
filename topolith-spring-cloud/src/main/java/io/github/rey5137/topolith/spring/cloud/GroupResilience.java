package io.github.rey5137.topolith.spring.cloud;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import io.github.rey5137.topolith.spring.boot.internal.ResilienceModules;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceRefresh;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceSettings;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * The running retry policy and circuit breaker of each external and loopback module, in references the group's advice
 * and decorator read once per call. A refresh swaps only the reference of the group whose keys changed (R-EXT-14,
 * R-EXT-15, D-104); a call in flight keeps what it read.
 */
final class GroupResilience implements ResilienceRefresh {
    static final boolean COMMONS_PRESENT = ClassUtils.isPresent(TopolithResilienceAutoConfiguration.COMMONS,
            GroupResilience.class.getClassLoader());

    private final Map<String, AtomicReference<RetryAdvice.Policy>> retries = new TreeMap<>();
    private final Map<String, AtomicReference<BreakerCall>> breakers = new TreeMap<>();
    private final ListableBeanFactory beans;
    private final Environment environment;
    private final boolean globalBreaker;

    GroupResilience(ResilienceModules modules, Map<String, BreakerCall> started, ListableBeanFactory beans,
            Environment environment, boolean globalBreaker) {
        this.beans = beans;
        this.environment = environment;
        this.globalBreaker = globalBreaker;
        modules.ids().forEach(id -> {
            retries.put(id, new AtomicReference<>(RetryAdvice.policy(modules.module(id).retry())));
            breakers.put(id, new AtomicReference<>(started.get(id)));
        });
    }

    AtomicReference<RetryAdvice.Policy> retry(String id) {
        return retries.get(id);
    }

    AtomicReference<BreakerCall> breaker(String id) {
        return breakers.get(id);
    }

    @Override
    public boolean globalBreaker() {
        return globalBreaker;
    }

    @Override
    public synchronized void apply(String id, ResilienceSettings.Retry retry, ResilienceSettings.Breaker breaker) {
        // everything that can fail is built before anything is published
        RetryAdvice.Policy policy = retry == null ? null : RetryAdvice.policy(retry);
        AtomicReference<BreakerCall> running = breakers.get(id);
        BreakerCall current = running.get();
        BreakerCall rebuilt = null;
        if (breaker != null && breaker.enabled()) {
            if (!COMMONS_PRESENT) {
                throw TopolithResilienceAutoConfiguration.factoryProblem(List.of(id), Map.of());
            }
            // replacing is only for Topolith's own instance: a host's breaker of the same name is a conflict (TL0003)
            rebuilt = CircuitBreakers.create(beans, environment, Map.of(id, breaker),
                    current != null && current.registered()).get(id);
        }
        if (policy != null) {
            retries.get(id).set(policy);
        }
        if (breaker != null) {
            boolean owned = current != null && current.registered();
            running.set(rebuilt);
            // only Topolith's own breaker is ever removed: a host's breaker of the same name is not ours (D-105)
            if (rebuilt == null && owned && COMMONS_PRESENT) {
                CircuitBreakers.remove(beans, id);
            }
        }
    }
}
