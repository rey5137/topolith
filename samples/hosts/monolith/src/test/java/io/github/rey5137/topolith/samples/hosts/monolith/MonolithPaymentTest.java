package io.github.rey5137.topolith.samples.hosts.monolith;

import java.math.BigDecimal;
import java.util.UUID;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.samples.payment.api.ChargeRequest;
import io.github.rey5137.topolith.samples.payment.api.PaymentDeclinedException;
import io.github.rey5137.topolith.samples.paymenttests.AbstractPaymentTest;
import io.github.rey5137.topolith.samples.user.UserAutoConfiguration;
import io.github.rey5137.topolith.samples.user.api.UserApi;
import io.github.rey5137.topolith.spring.test.TopolithByValue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The internal column of the payment mode matrix: payment runs in-process behind the by-value proxy (R-TEST-05). */
// snippet:start monolith-payment-test
@SpringBootTest(classes = MonolithApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TopolithByValue("payment")
class MonolithPaymentTest extends AbstractPaymentTest {
    @Override
    protected Mode paymentMode() {
        return Mode.INTERNAL;
    }
// snippet:end monolith-payment-test

    @Autowired
    ApplicationContext context;

    /** Positive control for the payment host's absence check: here the same user beans really exist. */
    @Test
    void user_module_registers_its_auto_configuration_and_contract_bean() {
        assertEquals(1, context.getBeanNamesForType(UserAutoConfiguration.UserMarker.class).length);
        assertEquals(1, context.getBeanNamesForType(UserApi.class).length);
    }

    /** The proxy is really in front of payment: the caller gets a rebuilt copy of the provider's declared exception. */
    @Test
    void ac_test_14_by_value_proxy_hands_the_caller_a_copy_of_the_declared_exception() {
        ChargeRequest request = new ChargeRequest("declined-" + UUID.randomUUID(), new BigDecimal("1000000"));
        PaymentDeclinedException declined = assertThrows(PaymentDeclinedException.class,
                () -> paymentApi.charge(request));
        assertEquals(1, declined.getSuppressed().length);
        PaymentDeclinedException original = assertInstanceOf(PaymentDeclinedException.class,
                declined.getSuppressed()[0]);
        assertNotSame(original, declined);
        assertEquals(original.getMessage(), declined.getMessage());
    }
}
