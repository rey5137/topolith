package io.github.rey5137.topolith.spring.test;

import java.time.Duration;
import java.util.Objects;

import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.spring.boot.internal.VersionChecks;
import org.springframework.context.ApplicationContext;

/** Helpers for waiting on asynchronous Topolith work in tests. */
@Incubating
public final class TopolithTestSupport {
    private TopolithTestSupport() { }

    /**
     * Waits for remote version checks started at readiness or refresh to finish and log. Call this before asserting
     * on TL0015 or TL0023 (D-118). Returns immediately with {@code true} when there is no external module or readiness
     * has not started the checks. Returns {@code false} on timeout or interruption; interruption restores the thread's
     * interrupt flag.
     *
     * @param context the application context
     * @param timeout the maximum time to wait
     * @return whether the checks have finished
     * @throws IllegalArgumentException if the timeout is negative
     * @throws NullPointerException if either argument is null
     */
    public static boolean awaitVersionChecks(ApplicationContext context, Duration timeout) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        VersionChecks checks = context.getBeanProvider(VersionChecks.class).getIfAvailable();
        return checks == null || checks.await(timeout);
    }
}
