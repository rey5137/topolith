package io.github.rey5137.topolith.samples.hosts.monolith;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.samples.payment.api.ChargeRequest;
import io.github.rey5137.topolith.samples.payment.api.ChargeResult;
import io.github.rey5137.topolith.samples.payment.api.PaymentApi;
import io.github.rey5137.topolith.samples.payment.api.PaymentNotFoundException;
import io.github.rey5137.topolith.spring.test.TopolithModuleMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Retry in the loopback column (AC-TEST-24): the {@code @Idempotent} read is repeated after a seeded fault and
 * recovers, the payment mutation is attempted once. Seed 4096 at rate 0.5 fails the first call and passes the second
 * (R-EXT-10); the listener of {@code topolith-spring-test} restarts that sequence for each test method.
 */
// snippet:start monolith-loopback-retry-test
@SpringBootTest(classes = MonolithApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:h2:mem:monolith-retry;DB_CLOSE_DELAY=-1",
                "topolith.modules.payment.remote.retry.max-attempts=3",
                "topolith.modules.payment.remote.retry.initial-backoff=1ms",
                "topolith.modules.payment.loopback.fault.rate=0.5",
                "topolith.modules.payment.loopback.fault.seed=4096"})
@TopolithModuleMode(id = "payment", mode = Mode.LOOPBACK)
class MonolithLoopbackRetryTest {
    @Autowired
    PaymentApi paymentApi;

    @Test
    void ac_test_24_an_idempotent_read_retries_past_a_seeded_fault_and_recovers() {
        // the first attempt is failed by the fault; the retry reaches the provider, which answers "not found"
        assertThrows(PaymentNotFoundException.class, () -> paymentApi.find("missing-" + UUID.randomUUID()));
    }

    @Test
    void ac_test_24_a_non_idempotent_mutation_is_attempted_once_and_fails() {
        ChargeRequest request = new ChargeRequest("retry-" + UUID.randomUUID(), new BigDecimal("10.00"), List.of());
        assertThrows(ModuleUnavailableException.class, () -> paymentApi.charge(request));
        // the fault drew once: the next call is the sequence's second draw, which passes; a retry would have used it
        ChargeResult charged = paymentApi.charge(request);
        assertNotNull(charged.id());
        assertEquals(request.reference(), charged.reference());
    }
}
// snippet:end monolith-loopback-retry-test
