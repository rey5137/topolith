package io.github.rey5137.topolith.spring.boot.internal;

import io.github.rey5137.topolith.core.Incubating;

/**
 * Implemented by the bean that owns every loopback module's fault sequence (R-EXT-10). The test execution listener of
 * {@code topolith-spring-test} restarts them before each test method. Not public API (D-78).
 */
@Incubating
public interface FaultSequences {
    /** Restarts each loopback module's fault sequence from its seed. */
    void restart();
}
