package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Incubating;

/** A host-provided string context carried across module routes (R-EXT-12, D-122). */
@Incubating
public interface ContextCarrier {
    /** The header name suffix identifying this carrier. */
    String name();

    /** The current value, or null. */
    String current();

    /** Makes the value current until the returned scope closes; null makes none current. */
    Scope open(String value);

    /** Restores the previous value. */
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
