package io.github.rey5137.topolith.spring.cloud;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.rey5137.topolith.spring.boot.internal.Outcomes;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceModules;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceSettings;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Retries an idempotent exchange of one group on a transient provider failure (R-EXT-14). It sits inside
 * {@code ContractCallTranslator}, so the translation sees the final failure, and outside the circuit breaker, so each
 * attempt enters it.
 */
final class RetryAdvice implements MethodInterceptor {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final String module;
    private final Class<?> service;
    private final ResilienceModules modules;
    private final AtomicReference<Policy> policy;
    private final AtomicReference<BreakerCall> breaker;

    /** One module's retry settings and the template built from them, swapped as a whole on refresh (D-104). */
    record Policy(ResilienceSettings.Retry settings, RetryTemplate template) {
    }

    RetryAdvice(String module, Class<?> service, ResilienceModules modules, AtomicReference<Policy> policy,
            AtomicReference<BreakerCall> breaker) {
        this.breaker = breaker;
        this.module = module;
        this.service = service;
        this.modules = modules;
        this.policy = policy;
    }

    static Policy policy(ResilienceSettings.Retry settings) {
        RetryPolicy.Builder policy = RetryPolicy.builder().maxRetries(settings.maxAttempts() - 1L)
                .delay(settings.initialBackoff()).multiplier(settings.multiplier()).jitter(Duration.ZERO)
                .predicate(failure -> Outcomes.classify(failure) == Outcomes.Outcome.TRANSIENT);
        // Spring's policy rejects a zero maximum delay: with none, there is no backoff to cap
        if (!settings.maxBackoff().isZero()) {
            policy.maxDelay(settings.maxBackoff());
        }
        return new Policy(settings, new RetryTemplate(policy.build()));
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        // the call's breaker is captured with its policy, once, for every attempt (D-107)
        try (CallScope.Scope scope = CallScope.open(breaker)) {
            return invokeWith(invocation);
        }
    }

    private Object invokeWith(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        // captured once: a call in flight finishes on the policy it started with
        Policy current = policy.get();
        if (!current.settings().active() || !(invocation instanceof ProxyMethodInvocation call)
                || method.getDeclaringClass() == Object.class || method.isDefault()
                || !idempotent(method, current.settings())) {
            return invocation.proceed();
        }
        try {
            // each attempt runs a fresh copy of the rest of the chain: a proceeded invocation cannot run twice
            return current.template().execute(() -> call.invocableClone().proceed());
        } catch (RetryException exhausted) {
            throw exhausted.getLastException();
        }
    }

    /** Whether the descriptor lists the method, or {@code safe-methods-idempotent} admits its HTTP method (D-100). */
    private boolean idempotent(Method method, ResilienceSettings.Retry settings) {
        return modules.declaredIdempotent(module, service, method)
                || settings.safeMethodsIdempotent() && SAFE.contains(httpMethod(method));
    }

    /** The HTTP method of the exchange on the caller's interface: a method-level value wins over a type-level one. */
    private String httpMethod(Method method) {
        HttpExchange own = AnnotatedElementUtils.findMergedAnnotation(method, HttpExchange.class);
        String verb = own == null ? "" : own.method();
        if (verb.isEmpty()) {
            HttpExchange type = AnnotatedElementUtils.findMergedAnnotation(service, HttpExchange.class);
            verb = type == null ? "" : type.method();
        }
        return verb.toUpperCase(Locale.ROOT);
    }
}
