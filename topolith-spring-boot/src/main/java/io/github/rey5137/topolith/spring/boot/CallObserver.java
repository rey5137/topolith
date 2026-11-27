package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Mode;

/**
 * Observes one contract call (R-EXT-11). It names no Micrometer type, so the advice that holds it loads without
 * Micrometer on the classpath; the one bean of this type exists only when Micrometer Observation does.
 */
interface CallObserver {
    /** The call being observed. */
    interface Call {
        Object proceed() throws Throwable;
    }

    /** Runs {@code call} and records it as one {@code topolith.module.call}, or just runs it when nothing observes. */
    Object observe(String module, Mode mode, Class<?> contract, String method, Call call) throws Throwable;

    /** A scope reopened on another thread; closing it makes the previous observation current again. */
    interface Reopened extends AutoCloseable {
        @Override
        void close();
    }

    /** The observation current on this thread, as an opaque handle, or {@code null} when nothing is observed. */
    default Object current() {
        return null;
    }

    /** Makes {@code captured} current on this thread, or returns {@code null} when it already is or nothing applies. */
    default Reopened reopen(Object captured) {
        return null;
    }
}
