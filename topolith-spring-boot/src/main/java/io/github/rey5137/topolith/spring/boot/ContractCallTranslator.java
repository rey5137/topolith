package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.spring.boot.internal.Outcomes;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The first advice of an external module's service proxy (R-EXT-04, D-43). A Topolith problem naming a declared class
 * the caller knows is rebuilt as that class; a transport failure becomes {@link ModuleUnavailableException}; anything
 * else propagates unchanged. No Java serialization and no polymorphic typing: only a class name in a known api package
 * and a message cross.
 */
final class ContractCallTranslator implements MethodInterceptor {
    /** The prefix of Spring Cloud LoadBalancer's no-instance {@code IllegalStateException}. */
    static final String NO_INSTANCES = Outcomes.NO_INSTANCES;
    /** The kind of a {@link ModuleUnavailableException} propagated by the provider. */
    static final String DOWNSTREAM = "unavailable downstream";
    private static final int MAX_NAME_LENGTH = 512;
    private static final Pattern BINARY_NAME = Pattern.compile(
            "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}]"
                    + "[\\p{javaJavaIdentifierPart}]*)+");
    /** The D-27 module id rule, as the descriptor parser applies it. */
    private static final Pattern MODULE_ID = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final int MAX_ID_LENGTH = 63;

    private final String module;
    private final Set<String> apiPackages;
    private final ClassLoader contractLoader;

    ContractCallTranslator(String module, Set<String> apiPackages, ClassLoader contractLoader) {
        this.module = module;
        this.apiPackages = apiPackages;
        this.contractLoader = contractLoader;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        try {
            return invocation.proceed();
        } catch (RuntimeException e) {
            Throwable translated = translateOne(e, invocation.getMethod());
            // Spring Cloud CircuitBreaker wraps the failure in NoFallbackAvailableException; look one level down
            if (translated == e && noFallback(e)) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    Throwable inner = translateOne(runtime, invocation.getMethod());
                    if (inner != runtime) {
                        translated = inner;
                    }
                } else if (cause instanceof TimeoutException) {
                    translated = unavailable(module, "timeout", e);
                }
            }
            if (translated == e && (circuitOpen(e) || circuitOpen(e.getCause()))) {
                translated = unavailable(module, "circuit open", e);
            }
            throw translated;
        }
    }

    /** The translation of one exception, or the exception itself when it is not one this advice maps. */
    private Throwable translateOne(RuntimeException e, Method method) {
        if (e instanceof RestClientResponseException response) {
            return translate(response, method);
        }
        if (e instanceof ResourceAccessException access) {
            return unavailable(module, timeout(access) ? "timeout" : "connection failure", access);
        }
        if (e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().startsWith(NO_INSTANCES)) {
            return unavailable(module, "no instance", e);
        }
        // a client-credentials token request that fails is wrapped by Spring Security (D-97); only that wrapper is
        // unwrapped, found by class name so no OAuth2 class is named (INV-7). A host's own wrapper propagates as
        // thrown (D-51), and a circuit breaker's wrapper is handled by invoke
        if (oauth2Wrapper(e)) {
            int depth = 0;
            for (Throwable cause = e.getCause(); cause != null && depth < 8; cause = cause.getCause(), depth++) {
                if (cause instanceof ResourceAccessException access) {
                    return unavailable(module, timeout(access) ? "timeout" : "connection failure", e);
                }
                if (cause instanceof RestClientResponseException response
                        && (response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429)) {
                    return unavailable(module, "connection failure", e);
                }
            }
        }
        return e;
    }

    /** Spring Cloud CircuitBreaker's wrapper for a failed call without a fallback, found by class name. */
    private static boolean noFallback(Throwable e) {
        return e.getClass().getSimpleName().equals("NoFallbackAvailableException");
    }

    /** Spring Security's token-request failure, found by class name up the superclass chain (D-97). */
    private static boolean oauth2Wrapper(Throwable e) {
        for (Class<?> type = e.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("org.springframework.security.oauth2.core.OAuth2AuthorizationException")) {
                return true;
            }
        }
        return false;
    }

    /** A circuit breaker's open-circuit rejection, found by class name so no breaker library is a dependency. */
    private static boolean circuitOpen(Throwable e) {
        return e != null && e.getClass().getSimpleName().equals("CallNotPermittedException");
    }

    private Throwable translate(RestClientResponseException e, Method method) {
        ProblemDetail problem = Outcomes.problem(e);
        if (problem != null) {
            Object name = problem.getProperties() == null ? null : problem.getProperties().get(ModuleExceptionResolver
                    .EXCEPTION);
            Throwable rebuilt = name instanceof String className ? rebuild(className, problem, e, method) : null;
            if (rebuilt != null) {
                return rebuilt;
            }
        } else if (e.getStatusCode().value() == 502 || e.getStatusCode().value() == 503
                || e.getStatusCode().value() == 504) {
            return unavailable(module, "status " + e.getStatusCode().value(), e);
        }
        int status = e.getStatusCode().value();
        if (problem == null && Set.of(401, 403, 404, 405, 415).contains(status)
                && (e.getResponseHeaders() == null || e.getResponseHeaders().getFirst("Topolith-Module") == null)) {
            return unavailable(module, "status " + status, e);
        }
        return e;
    }

    private Throwable rebuild(String className, ProblemDetail problem, RestClientResponseException e, Method method) {
        String detail = problem.getDetail();
        if (className.equals(ModuleUnavailableException.class.getName())) {
            Object id = problem.getProperties().get(ModuleExceptionResolver.MODULE);
            if (id instanceof String moduleId && moduleId.length() <= MAX_ID_LENGTH
                    && MODULE_ID.matcher(moduleId).matches()) {
                return unavailable(moduleId, DOWNSTREAM, e);
            }
            return null;
        }
        int dot = className.lastIndexOf('.');
        if (className.length() > MAX_NAME_LENGTH || !BINARY_NAME.matcher(className).matches()
                || !(apiPackages.contains(className.substring(0, dot))
                        || Set.of(IllegalArgumentException.class.getName(), IllegalStateException.class.getName(),
                                UnsupportedOperationException.class.getName()).contains(className))) {
            return null;
        }
        try {
            Class<?> type = Class.forName(className, false, contractLoader);
            if (!Exception.class.isAssignableFrom(type) || !Modifier.isPublic(type.getModifiers())
                    || Modifier.isAbstract(type.getModifiers())
                    || (!RuntimeException.class.isAssignableFrom(type)
                            && Arrays.stream(method.getExceptionTypes()).noneMatch(t -> t.isAssignableFrom(type)))) {
                return null;
            }
            Exception rebuilt = construct(type.asSubclass(Exception.class), detail);
            if (rebuilt != null) {
                rebuilt.addSuppressed(e);
            }
            return rebuilt;
        } catch (ClassNotFoundException | LinkageError failure) {
            return null;
        }
    }

    private static Exception construct(Class<? extends Exception> type, String detail) {
        try {
            Constructor<? extends Exception> withMessage = type.getConstructor(String.class);
            return withMessage.newInstance(detail);
        } catch (NoSuchMethodException e) {
            try {
                return type.getConstructor().newInstance();
            } catch (ReflectiveOperationException | RuntimeException none) {
                return null;
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * A {@link ModuleUnavailableException} with a fixed message: it is declared, so the next provider sends its
     * message on, and URLs or downstream bodies must stay in the cause.
     */
    static ModuleUnavailableException unavailable(String module, String kind, Exception cause) {
        return new ModuleUnavailableException(module, message(module, kind), cause);
    }

    /** The only message a {@link ModuleUnavailableException} crossing HTTP carries. */
    static String message(String module, String kind) {
        return "module '" + module + "' could not be reached (" + kind + ")";
    }

    private static boolean timeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
