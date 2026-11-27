package io.github.rey5137.topolith.samples.paymenttests;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.samples.payment.api.ChargeRequest;
import io.github.rey5137.topolith.samples.payment.api.ChargeResult;
import io.github.rey5137.topolith.samples.payment.api.PaymentApi;
import io.github.rey5137.topolith.samples.payment.api.PaymentDeclinedException;
import io.github.rey5137.topolith.samples.payment.api.PaymentNotFoundException;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// snippet:start shared-payment-test
/** The payment contract every sample host must satisfy (AC-TEST-05); shipped as this module's test-jar. */
public abstract class AbstractPaymentTest {
    @Autowired
    protected PaymentApi paymentApi;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected Environment environment;

    @Test
    void ac_test_05_charge_then_find_returns_the_stored_charge() {
        String reference = "charge-" + UUID.randomUUID();
        ChargeRequest request = new ChargeRequest(reference, new BigDecimal("12.50"));
        ChargeResult charged = paymentApi.charge(request);
        assertNotNull(charged.id());
        assertEquals("CHARGED", charged.status());
        ChargeResult found = paymentApi.find(charged.id());
        assertEquals(charged.id(), found.id());
        assertEquals(reference, found.reference());
        assertEquals(0, request.amount().compareTo(found.amount()));
    }
// snippet:end shared-payment-test

    @Autowired
    protected Topology topology;

    @Autowired
    protected TestObservationRegistry observations;

    /** The mode this run names for payment: each subclass is one column of the mode matrix (R-TEST-05). */
    protected abstract Mode paymentMode();

    @Test
    void ac_test_14_payment_runs_in_the_mode_this_run_names() {
        assertEquals(paymentMode(), topology.mode("payment"));
    }

    /** The same name and keys in every column of the matrix, but for {@code module.mode} (AC-TEST-21). */
    @Test
    void ac_test_21_a_contract_call_records_one_observation_named_the_same_in_every_mode() {
        observations.clear();
        ChargeResult charged = paymentApi.charge(new ChargeRequest("observed-" + UUID.randomUUID(),
                new BigDecimal("3.00")));
        assertNotNull(charged.id());
        assertCall("charge", "none");
        observations.clear();
        assertThrows(PaymentNotFoundException.class, () -> paymentApi.find("missing-" + UUID.randomUUID()));
        assertCall("find", "PaymentNotFoundException");
    }

    private void assertCall(String method, String exception) {
        TestObservationRegistryAssert.assertThat(observations)
                .hasNumberOfObservationsWithNameEqualTo("topolith.module.call", 1)
                .hasObservationWithNameEqualTo("topolith.module.call").that()
                .hasContextualNameEqualTo("payment PaymentApi." + method)
                .hasLowCardinalityKeyValue("module.id", "payment")
                .hasLowCardinalityKeyValue("module.mode", paymentMode().id())
                .hasLowCardinalityKeyValue("caller.module", "_host")
                .hasLowCardinalityKeyValue("contract", PaymentApi.class.getName())
                .hasLowCardinalityKeyValue("method", method)
                .hasLowCardinalityKeyValue("exception", exception);
    }

    @Test
    void ac_test_05_declined_charge_keeps_the_declared_type() {
        ChargeRequest request = new ChargeRequest("declined-" + UUID.randomUUID(), new BigDecimal("1000000"));
        assertThrows(PaymentDeclinedException.class, () -> paymentApi.charge(request));
    }

    @Test
    void ac_test_05_unknown_payment_keeps_the_declared_type() {
        assertThrows(PaymentNotFoundException.class, () -> paymentApi.find("missing-" + UUID.randomUUID()));
    }

    @Test
    void ac_test_05_caller_edit_after_charge_does_not_change_the_stored_tags() {
        List<String> tags = new ArrayList<>(List.of("first", "second"));
        ChargeRequest request = new ChargeRequest("value-" + UUID.randomUUID(), new BigDecimal("7.00"), tags);
        ChargeResult charged = paymentApi.charge(request);
        tags.add("added-after-charge");
        tags.remove("first");
        // The caller's record (shared with the provider in internal mode) must not follow the edit.
        assertEquals(List.of("first", "second"), request.tags());
        assertEquals(List.of("first", "second"), charged.tags());
        ChargeResult found = paymentApi.find(charged.id());
        assertEquals(List.of("first", "second"), found.tags());
        assertThrows(UnsupportedOperationException.class, () -> found.tags().add("x"));
    }

    @Test
    void ac_test_05_user_route_charges_payment_when_user_is_internal() {
        Assumptions.assumeTrue("internal".equalsIgnoreCase(environment.getProperty("topolith.modules.user.mode")),
                "user is not internal in this topology");
        Map<String, Object> request = new HashMap<>();
        request.put("reference", "order-" + UUID.randomUUID());
        request.put("amount", 25.00);
        ResponseEntity<Map> response = rest.postForEntity("/user/orders", request, Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("paymentId"));
    }
}
