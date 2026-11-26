package io.github.rey5137.topolith.samples.hosts.monolith;

import java.util.ArrayList;
import java.util.List;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.samples.payment.api.PaymentApi;
import io.github.rey5137.topolith.samples.payment.api.PaymentNotFoundException;
import io.github.rey5137.topolith.spring.test.TopolithModuleMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A seeded loopback fault sequence restarts per test method, so two methods sharing this context fail alike. */
@SpringBootTest(classes = MonolithApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:h2:mem:monolith-fault;DB_CLOSE_DELAY=-1",
                "topolith.modules.payment.loopback.fault.rate=0.5",
                "topolith.modules.payment.loopback.fault.kind=unavailable",
                "topolith.modules.payment.loopback.fault.seed=42"})
@TopolithModuleMode(id = "payment", mode = Mode.LOOPBACK)
class MonolithLoopbackFaultTest {
    private static final int CALLS = 20;
    private static List<Boolean> firstSeen;

    @Autowired
    PaymentApi paymentApi;

    @Test
    void ac_ext_38_one_method_draws_the_seeded_sequence_from_its_start() {
        assertSameAsTheOtherMethod(failures());
    }

    @Test
    void ac_ext_38_another_method_in_the_same_context_draws_the_same_sequence() {
        assertSameAsTheOtherMethod(failures());
    }

    private static synchronized void assertSameAsTheOtherMethod(List<Boolean> failures) {
        assertTrue(failures.contains(true) && failures.contains(false), failures.toString());
        if (firstSeen == null) {
            firstSeen = failures;
        } else {
            assertEquals(firstSeen, failures);
        }
    }

    /** Which of the calls the fault injection failed, in call order; the others reach the provider over HTTP. */
    private List<Boolean> failures() {
        List<Boolean> failed = new ArrayList<>();
        for (int i = 0; i < CALLS; i++) {
            try {
                paymentApi.find("missing-" + i);
                throw new AssertionError("an unknown payment was found");
            } catch (ModuleUnavailableException e) {
                failed.add(true);
            } catch (PaymentNotFoundException e) {
                failed.add(false);
            }
        }
        return failed;
    }
}
