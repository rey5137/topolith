package io.github.rey5137.topolith.spring.boot.observation;

import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.core.Mode;
import io.micrometer.observation.Observation;

/**
 * The context of one {@code topolith.module.call} observation (R-EXT-11): the module called, its mode, the module the
 * call came from, the contract and method, and how the call ended. Topolith fills it; a convention only reads it.
 */
@Incubating
public final class ModuleCallContext extends Observation.Context {
    /** The caller module of a call made from outside every module. */
    public static final String HOST = "_host";
    /** The {@link #exception()} of a call that returned. */
    public static final String NO_EXCEPTION = "none";
    /** The {@link #exception()} of a call that failed with an exception its contract does not declare. */
    public static final String UNDECLARED = "undeclared";

    private final String moduleId;
    private final Mode mode;
    private final Class<?> contract;
    private final String method;
    private volatile String callerModule = HOST;
    private volatile String exception = NO_EXCEPTION;

    /** For Topolith only: not for conventions or host code; may change without notice. */
    public ModuleCallContext(String moduleId, Mode mode, Class<?> contract, String method) {
        this.moduleId = moduleId;
        this.mode = mode;
        this.contract = contract;
        this.method = method;
    }

    /** The id of the module whose contract is called. */
    public String moduleId() {
        return moduleId;
    }

    /** The module's mode: internal, loopback or external. */
    public Mode mode() {
        return mode;
    }

    /** The id of the module the call came from, or {@value #HOST} for host code. */
    public String callerModule() {
        return callerModule;
    }

    /** The contract interface called. */
    public Class<?> contract() {
        return contract;
    }

    /** The name of the contract method called. */
    public String method() {
        return method;
    }

    /**
     * {@value #NO_EXCEPTION} when the call returned, else the simple name of the declared exception class the caller
     * received, or {@value #UNDECLARED}.
     */
    public String exception() {
        return exception;
    }

    /** For Topolith only: not for conventions or host code; may change without notice. */
    public void setCallerModule(String callerModule) {
        this.callerModule = callerModule;
    }

    /** For Topolith only: not for conventions or host code; may change without notice. */
    public void setException(String exception) {
        this.exception = exception;
    }
}
