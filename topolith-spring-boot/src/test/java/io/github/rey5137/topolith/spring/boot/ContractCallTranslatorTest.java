package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContractCallTranslatorTest {
    /** Stands in, by class name, for Spring Cloud CircuitBreaker's wrapper. */
    static final class NoFallbackAvailableException extends RuntimeException {
        NoFallbackAvailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static Object call(RuntimeException failure) throws Throwable {
        MethodInvocation invocation = (MethodInvocation) Proxy.newProxyInstance(
                ContractCallTranslatorTest.class.getClassLoader(), new Class<?>[] {MethodInvocation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("proceed")) {
                        throw failure;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new ContractCallTranslator("account", Set.of(), ContractCallTranslatorTest.class.getClassLoader())
                .invoke(invocation);
    }

    @Test
    void ac_ext_10_a_host_wrapper_that_is_not_the_circuit_breakers_propagates_unchanged() {
        RuntimeException wrapper = new IllegalStateException("host wrapper", new RestClientResponseException(
                "boom", 503, "Service Unavailable", null, null, null));
        assertSame(wrapper, assertThrows(IllegalStateException.class, () -> call(wrapper)));
    }

    @Test
    void ac_ext_10_a_time_limiter_timeout_under_the_circuit_breaker_wrapper_is_unavailable() {
        ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class, () -> call(
                new NoFallbackAvailableException("No fallback available.", new TimeoutException())));
        assertEquals("module 'account' could not be reached (timeout)", e.getMessage());
    }

    private static OAuth2AuthorizationException tokenFailure(Throwable cause) {
        return new OAuth2AuthorizationException(new OAuth2Error("invalid_token_response"), cause);
    }

    private static final String UNAVAILABLE = "module 'account' could not be reached (connection failure)";

    @Test
    void ac_ext_61_a_host_wrapper_around_an_io_failure_propagates_unchanged() {
        RuntimeException wrapper = new IllegalStateException("host wrapper", new ResourceAccessException("down"));
        assertSame(wrapper, assertThrows(IllegalStateException.class, () -> call(wrapper)));
    }

    @Test
    void ac_ext_61_a_token_request_ending_in_io_failure_is_unavailable() {
        ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                () -> call(tokenFailure(new ResourceAccessException("down"))));
        assertEquals(UNAVAILABLE, e.getMessage());
    }

    @Test
    void ac_ext_61_a_token_request_answered_5xx_or_429_is_unavailable() {
        ModuleUnavailableException server = assertThrows(ModuleUnavailableException.class,
                () -> call(tokenFailure(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))));
        assertEquals(UNAVAILABLE, server.getMessage());
        ModuleUnavailableException limited = assertThrows(ModuleUnavailableException.class,
                () -> call(tokenFailure(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))));
        assertEquals(UNAVAILABLE, limited.getMessage());
    }

    @Test
    void ac_ext_61_a_token_request_answered_4xx_or_without_a_cause_propagates_unchanged() {
        RuntimeException refused = tokenFailure(new HttpClientErrorException(HttpStatus.BAD_REQUEST));
        assertSame(refused, assertThrows(OAuth2AuthorizationException.class, () -> call(refused)));
        RuntimeException empty = tokenFailure(null);
        assertSame(empty, assertThrows(OAuth2AuthorizationException.class, () -> call(empty)));
    }

    @Test
    void ac_ext_61_a_token_failure_under_the_circuit_breaker_wrapper_is_unavailable() {
        ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class, () -> call(
                new NoFallbackAvailableException("No fallback available.", tokenFailure(
                        new HttpServerErrorException(HttpStatus.BAD_GATEWAY)))));
        assertEquals(UNAVAILABLE, e.getMessage());
    }

    @Test
    void ac_ext_61_a_wrapper_inside_the_token_failure_is_looked_through() {
        ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class, () -> call(
                tokenFailure(new IllegalStateException("inner", new HttpServerErrorException(
                        HttpStatus.INTERNAL_SERVER_ERROR)))));
        assertEquals(UNAVAILABLE, e.getMessage());
    }
}
