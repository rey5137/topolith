package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.http.MediaType;
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
    static final String NO_INSTANCES = "No instances available for";
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
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw unavailable(module, timeout(e) ? "timeout" : "connection failure", e);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith(NO_INSTANCES)) {
                throw unavailable(module, "no instance", e);
            }
            throw e;
        }
    }

    private RuntimeException translate(RestClientResponseException e) {
        ProblemDetail problem = problem(e);
        if (problem != null) {
            Object name = problem.getProperties() == null ? null : problem.getProperties().get(ModuleExceptionResolver
                    .EXCEPTION);
            RuntimeException rebuilt = name instanceof String className ? rebuild(className, problem, e) : null;
            if (rebuilt != null) {
                return rebuilt;
            }
        } else if (e.getStatusCode().value() == 502 || e.getStatusCode().value() == 503
                || e.getStatusCode().value() == 504) {
            return unavailable(module, "status " + e.getStatusCode().value(), e);
        }
        return e;
    }

    /** The response's Topolith problem, or null for any other body. */
    private static ProblemDetail problem(RestClientResponseException e) {
        MediaType type = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getContentType();
        if (type == null || !MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(type)) {
            return null;
        }
        try {
            ProblemDetail problem = e.getResponseBodyAs(ProblemDetail.class);
            return problem != null && problem.getType() != null
                    && ModuleExceptionResolver.TYPE.equals(problem.getType().toString()) ? problem : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private RuntimeException rebuild(String className, ProblemDetail problem, RestClientResponseException e) {
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
                || !apiPackages.contains(className.substring(0, dot))) {
            return null;
        }
        try {
            Class<?> type = Class.forName(className, false, contractLoader);
            if (!RuntimeException.class.isAssignableFrom(type) || !Modifier.isPublic(type.getModifiers())
                    || Modifier.isAbstract(type.getModifiers())) {
                return null;
            }
            RuntimeException rebuilt = construct(type.asSubclass(RuntimeException.class), detail);
            if (rebuilt != null) {
                rebuilt.addSuppressed(e);
            }
            return rebuilt;
        } catch (ClassNotFoundException | LinkageError failure) {
            return null;
        }
    }

    private static RuntimeException construct(Class<? extends RuntimeException> type, String detail) {
        try {
            Constructor<? extends RuntimeException> withMessage = type.getConstructor(String.class);
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
