package io.github.rey5137.topolith.core;

import java.util.Locale;
import java.util.Optional;

/** How a host runs a module (R-MODE-01). */
@Incubating
public enum Mode {
    /** The module's implementation runs in the host. */
    INTERNAL,
    /** The module is called through a remote client. */
    EXTERNAL,
    /**
     * The module's implementation runs in the host, as for {@link #INTERNAL}, but code outside it calls its contracts
     * over HTTP on the host's own server (R-MODE-06).
     */
    LOOPBACK,
    /** The module and its client are not activated. */
    NONE;

    /** The configuration value, such as {@code internal}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a configured value, trimmed and case-insensitive; empty when it is none of the modes. */
    public static Optional<Mode> parse(String value) {
        if (value != null) {
            String trimmed = value.trim();
            for (Mode mode : values()) {
                if (mode.id().equalsIgnoreCase(trimmed)) {
                    return Optional.of(mode);
                }
            }
        }
        return Optional.empty();
    }
}
