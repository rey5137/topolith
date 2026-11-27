package io.github.rey5137.topolith.samples.hosts.monolith;

import java.math.BigDecimal;
import java.util.List;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.samples.payment.api.ChargeRequest;
import io.github.rey5137.topolith.samples.payment.api.PaymentApi;
import io.github.rey5137.topolith.samples.payment.api.PaymentDeclinedException;
import io.github.rey5137.topolith.samples.payment.api.PaymentNotFoundException;
import io.github.rey5137.topolith.spring.test.TopolithMock;
import io.github.rey5137.topolith.spring.test.TopolithMockServer;
import io.github.rey5137.topolith.spring.test.TopolithModuleMode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The circuit breaker in the external column (AC-TEST-24): declared payment exceptions keep their type. */
@SpringBootTest(classes = MonolithApplication.class,
        properties = {"spring.datasource.url=jdbc:h2:mem:monolith-mock-breaker;DB_CLOSE_DELAY=-1",
                "topolith.modules.payment.remote.circuit-breaker.enabled=true",
                "topolith.modules.payment.remote.circuit-breaker.sliding-window-size=4",
                "topolith.modules.payment.remote.circuit-breaker.minimum-number-of-calls=4"})
@TopolithModuleMode(id = "user", mode = Mode.INTERNAL)
@TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
class MonolithMockBreakerTest {
    @TopolithMock(id = "payment")
    TopolithMockServer paymentMock;

    @Autowired
    PaymentApi paymentApi;

    @Autowired
    CircuitBreakerRegistry breakers;

    @Test
    void ac_test_24_declared_payment_exceptions_survive_the_breaker_over_external_http() {
        paymentMock.problem("GET", "/charges/missing", PaymentNotFoundException.class, "no such payment");
        paymentMock.problem("POST", "/charges", PaymentDeclinedException.class, "declined");
        ChargeRequest request = new ChargeRequest("declined-1", BigDecimal.TEN, List.of());
        for (int i = 0; i < 10; i++) {
            assertThrows(PaymentNotFoundException.class, () -> paymentApi.find("missing"));
            assertThrows(PaymentDeclinedException.class, () -> paymentApi.charge(request));
        }
        paymentMock.stub("GET", "/charges/found", 200,
                "{\"id\":\"chg-1\",\"reference\":\"r\",\"amount\":10.00,\"status\":\"CHARGED\",\"tags\":[]}");
        assertEquals("chg-1", paymentApi.find("found").id());
        CircuitBreaker breaker = breakers.find("payment").orElseThrow();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(1, breaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
    }
}
