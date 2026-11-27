package io.github.rey5137.topolith.spring.boot.internal;

import java.time.Duration;

import io.github.rey5137.topolith.core.Incubating;

/** Completion of remote version checks, shared with the test helper (D-109). */
@Incubating
public interface VersionChecks {
    /** Waits for checks already started, returning false on timeout or interruption. */
    boolean await(Duration timeout);
}
