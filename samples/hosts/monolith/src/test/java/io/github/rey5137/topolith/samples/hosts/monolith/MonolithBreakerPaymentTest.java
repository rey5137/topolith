package io.github.rey5137.topolith.samples.hosts.monolith;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.samples.payment.api.ChargeRequest;
import io.github.rey5137.topolith.samples.payment.api.ChargeResult;
import io.github.rey5137.topolith.samples.payment.api.PaymentApi;
import io.github.rey5137.topolith.samples.payment.api.PaymentDeclinedException;
import io.github.rey5137.topolith.samples.payment.api.PaymentNotFoundException;
import io.github.rey5137.topolith.spring.test.TopolithModuleMode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The circuit breaker in the loopback column (AC-TEST-24): a declared payment exception reaches the caller as its own
 * type, call after call, and never opens the circuit.
 */
// snippet:start monolith-breaker-payment-test
@SpringBootTest(classes = MonolithApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:h2:mem:monolith-breaker;DB_CLOSE_DELAY=-1",
                "topolith.modules.payment.remote.circuit-breaker.enabled=true",
                "topolith.modules.payment.remote.circuit-breaker.sliding-window-size=4",
                "topolith.modules.payment.remote.circuit-breaker.minimum-number-of-calls=4"})
@TopolithModuleMode(id = "payment", mode = Mode.LOOPBACK)
class MonolithBreakerPaymentTest {
    @Autowired
    PaymentApi paymentApi;

    @Autowired
    CircuitBreakerRegistry breakers;

    @Test
    void ac_test_24_declared_payment_exceptions_survive_the_breaker_and_leave_it_closed() {
        ChargeRequest declined = new ChargeRequest("declined-" + UUID.randomUUID(), new BigDecimal("1000000"),
                List.of());
        for (int i = 0; i < 10; i++) {
            assertThrows(PaymentNotFoundException.class, () -> paymentApi.find("missing-" + UUID.randomUUID()));
            assertThrows(PaymentDeclinedException.class, () -> paymentApi.charge(declined));
        }
        ChargeResult charged = paymentApi.charge(new ChargeRequest("ok-" + UUID.randomUUID(), BigDecimal.TEN,
                List.of()));
        assertEquals("CHARGED", charged.status());
        // the breaker is Topolith's own, and only the success counted: the declared exceptions were ignored
        CircuitBreaker breaker = breakers.find("payment").orElseThrow();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(1, breaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
    }
}
// snippet:end monolith-breaker-payment-test
