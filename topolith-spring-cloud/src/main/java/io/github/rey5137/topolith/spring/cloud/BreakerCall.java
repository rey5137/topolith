package io.github.rey5137.topolith.spring.cloud;

import java.util.function.Supplier;

/**
 * One module's circuit breaker, as the group's exchange decorator sees it: it runs the attempt on the calling thread
 * and rethrows what the attempt threw (R-EXT-15). It names no breaker library, so only the adapter does (D-105).
 */
interface BreakerCall {
    <T> T run(Supplier<T> attempt);

    /** Whether the breaker registry still holds this call's own breaker under its name (a host may have swapped it). */
    default boolean registered() {
        return false;
    }
}
