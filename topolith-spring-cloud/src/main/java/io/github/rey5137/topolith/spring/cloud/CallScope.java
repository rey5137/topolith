package io.github.rey5137.topolith.spring.cloud;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The circuit breaker a contract call captured when it started (D-107). Retry advice opens a scope on the calling
 * thread, so every attempt of the call runs through the same breaker instance whatever a refresh swaps meanwhile; an
 * attempt outside a scope reads the group's current breaker.
 */
final class CallScope {
    private static final BreakerCall NONE = new BreakerCall() {
        @Override
        public <T> T run(Supplier<T> attempt) {
            return attempt.get();
        }
    };
    private static final ThreadLocal<Map<AtomicReference<BreakerCall>, BreakerCall>> CAPTURED = new ThreadLocal<>();

    private CallScope() {
    }

    /** Captures {@code group}'s current breaker for the calling thread; close the scope when the call ends. */
    static Scope open(AtomicReference<BreakerCall> group) {
        Map<AtomicReference<BreakerCall>, BreakerCall> before = CAPTURED.get();
        Map<AtomicReference<BreakerCall>, BreakerCall> next = before == null ? new IdentityHashMap<>()
                : new IdentityHashMap<>(before);
        BreakerCall current = group.get();
        next.put(group, current == null ? NONE : current);
        CAPTURED.set(next);
        return () -> {
            if (before == null) {
                CAPTURED.remove();
            } else {
                CAPTURED.set(before);
            }
        };
    }

    /** The breaker for an attempt: the call's captured one, else the group's current one, else none. */
    static BreakerCall breaker(AtomicReference<BreakerCall> group) {
        Map<AtomicReference<BreakerCall>, BreakerCall> captured = CAPTURED.get();
        BreakerCall call = captured == null ? null : captured.get(group);
        if (call == null) {
            call = group.get();
        }
        return call == null ? NONE : call;
    }

    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
