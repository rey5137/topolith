package io.github.rey5137.topolith.spring.boot.internal;

import java.util.concurrent.TimeoutException;

import io.github.rey5137.topolith.core.Incubating;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The one classification of an exchange attempt's failure that retry, the circuit breaker and the contract translator
 * share (R-EXT-14, R-EXT-15, D-105). Not public API (D-109).
 */
@Incubating
public final class Outcomes {
    /** The {@code type} of a Topolith contract-exception problem (R-EXT-04). */
    public static final String PROBLEM_TYPE = "urn:topolith:problem:contract-exception";
    /** The prefix of Spring Cloud LoadBalancer's no-instance {@code IllegalStateException}. */
    public static final String NO_INSTANCES = "No instances available for";

    /** What a failed attempt means. */
    public enum Outcome {
        /** A transport or availability failure a retry may heal: the breaker records it and retry repeats it. */
        TRANSIENT,
        /** A provider-hop failure the breaker records but retry does not repeat (other 5xx, no instance). */
        FAILURE,
        /** The circuit breaker rejected the attempt: retry stops at once. */
        OPEN,
        /** Anything else: the breaker ignores it and retry does not repeat it. */
        OTHER;

        /** Whether the circuit breaker records the attempt as a failure. */
        public boolean breakerFailure() {
            return this == TRANSIENT || this == FAILURE;
        }
    }

    private Outcomes() {
    }

    /**
     * The outcome of {@code failure}. One Spring Cloud CircuitBreaker {@code NoFallbackAvailableException} is looked
     * through to its direct cause, as D-51 translation does; no other wrapper is.
     */
    public static Outcome classify(Throwable failure) {
        if (noFallback(failure)) {
            Throwable cause = failure.getCause();
            return cause == null ? Outcome.OTHER : classifyOne(cause);
        }
        return classifyOne(failure);
    }

    private static Outcome classifyOne(Throwable failure) {
        if (circuitOpen(failure)) {
            return Outcome.OPEN;
        }
        if (failure instanceof ResourceAccessException || failure instanceof TimeoutException) {
            return Outcome.TRANSIENT;
        }
        if (failure instanceof RestClientResponseException response) {
            if (problem(response) != null) {
                return Outcome.OTHER;
            }
            int status = response.getStatusCode().value();
            if (status == 502 || status == 503 || status == 504) {
                return Outcome.TRANSIENT;
            }
            return response.getStatusCode().is5xxServerError() ? Outcome.FAILURE : Outcome.OTHER;
        }
        if (failure instanceof IllegalStateException && failure.getMessage() != null
                && failure.getMessage().startsWith(NO_INSTANCES)) {
            return Outcome.FAILURE;
        }
        return Outcome.OTHER;
    }

    /** Spring Cloud CircuitBreaker's wrapper for a failed call without a fallback, found by class name. */
    public static boolean noFallback(Throwable failure) {
        return failure.getClass().getSimpleName().equals("NoFallbackAvailableException");
    }

    /** A circuit breaker's open-circuit rejection, found by class name so no breaker library is a dependency. */
    public static boolean circuitOpen(Throwable failure) {
        return failure != null && failure.getClass().getSimpleName().equals("CallNotPermittedException");
    }

    /** The response's Topolith problem, or null for any other body. */
    public static ProblemDetail problem(RestClientResponseException e) {
        MediaType type = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getContentType();
        if (type == null || !MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(type)) {
            return null;
        }
        try {
            ProblemDetail problem = e.getResponseBodyAs(ProblemDetail.class);
            return problem != null && problem.getType() != null
                    && PROBLEM_TYPE.equals(problem.getType().toString()) ? problem : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
