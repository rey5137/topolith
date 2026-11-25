package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
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
}
