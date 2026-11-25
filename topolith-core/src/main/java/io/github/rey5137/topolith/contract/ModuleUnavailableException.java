package io.github.rey5137.topolith.contract;

import java.util.Objects;

/**
 * The module named by {@link #moduleId()} could not be reached (R-CAT-02, D-43): an I/O failure or timeout, no
 * instance to call, a 502, 503 or 504 without a contract problem, or the provider's own call to another module
 * failing this way. It is a declared exception of every contract, so it crosses HTTP unchanged in every mode
 * (R-EXT-04). After a read timeout the call may have run. It is not a {@code TopolithException}: it reports a call,
 * not the topology (D-28).
 */
public final class ModuleUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String moduleId;

    public ModuleUnavailableException(String moduleId, String message) {
        super(message);
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
    }

    public ModuleUnavailableException(String moduleId, String message, Throwable cause) {
        super(message, cause);
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
    }

    /** The id of the module that could not be reached. */
    public String moduleId() {
        return moduleId;
    }
}
