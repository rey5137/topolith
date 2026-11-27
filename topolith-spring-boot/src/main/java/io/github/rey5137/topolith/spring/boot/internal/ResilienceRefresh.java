package io.github.rey5137.topolith.spring.boot.internal;

import io.github.rey5137.topolith.core.Incubating;

/**
 * What {@code topolith-spring-cloud} registers so a refresh can swap an external module's retry policy and circuit
 * breaker (R-EXT-14, R-EXT-15, D-104). {@code TopolithRefresh} validates the settings and calls it only for the group
 * whose keys changed; a thrown exception keeps the running policy or breaker. Not public API (D-78).
 */
@Incubating
public interface ResilienceRefresh {
    /**
     * Whether Spring Cloud's global HTTP-service breaker is applied to the groups, as found at startup (D-106): then a
     * module's retry or breaker cannot be turned on without a restart.
     */
    boolean globalBreaker();

    /**
     * Swaps module {@code id}'s retry policy and rebuilds its breaker (closed, or removed when not enabled); a
     * {@code null} part is left alone. Both are built before either is published, so an exception leaves both as they
     * were. Calls in flight keep what they captured (D-107).
     */
    void apply(String id, ResilienceSettings.Retry retry, ResilienceSettings.Breaker breaker);
}
