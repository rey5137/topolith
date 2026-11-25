package io.github.rey5137.topolith.core;

import java.io.Serializable;
import java.util.Objects;

/** One coded problem: what is wrong and how to fix it. */
@Incubating
public final class Diagnostic implements Serializable {
    private static final long serialVersionUID = 1L;

    private final TopolithCode code;
    private final String description;
    private final String action;
    private final TopolithCode.Severity severity;

    /** A diagnostic at its code's default severity. */
    public Diagnostic(TopolithCode code, String description, String action) {
        this(code, description, action, Objects.requireNonNull(code, "code").defaultSeverity());
    }

    /** A diagnostic at an effective severity that a later layer resolved (D-38). */
    public Diagnostic(TopolithCode code, String description, String action, TopolithCode.Severity severity) {
        this.code = Objects.requireNonNull(code, "code");
        this.description = Objects.requireNonNull(description, "description");
        this.action = Objects.requireNonNull(action, "action");
        this.severity = Objects.requireNonNull(severity, "severity");
    }

    public TopolithCode code() {
        return code;
    }

    /** The effective severity: the code's default unless a later layer overrode it (D-38). */
    public TopolithCode.Severity severity() {
        return severity;
    }

    /** What is wrong. */
    public String description() {
        return description;
    }

    /** How to fix it. */
    public String action() {
        return action;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Diagnostic that
                && code == that.code && description.equals(that.description) && action.equals(that.action)
                && severity == that.severity;
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, description, action, severity);
    }

    @Override
    public String toString() {
        return code.id() + ": " + description + " " + action;
    }
}
