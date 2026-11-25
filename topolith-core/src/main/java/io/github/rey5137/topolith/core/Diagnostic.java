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

    public Diagnostic(TopolithCode code, String description, String action) {
        this.code = Objects.requireNonNull(code, "code");
        this.description = Objects.requireNonNull(description, "description");
        this.action = Objects.requireNonNull(action, "action");
    }

    public TopolithCode code() {
        return code;
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
                && code == that.code && description.equals(that.description) && action.equals(that.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, description, action);
    }

    @Override
    public String toString() {
        return code.id() + ": " + description + " " + action;
    }
}
