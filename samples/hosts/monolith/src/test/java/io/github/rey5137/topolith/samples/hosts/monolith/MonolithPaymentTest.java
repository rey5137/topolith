package io.github.rey5137.topolith.samples.hosts.monolith;

import io.github.rey5137.topolith.samples.payment.tests.AbstractPaymentTest;
import io.github.rey5137.topolith.samples.user.UserAutoConfiguration;
import io.github.rey5137.topolith.samples.user.api.UserApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = MonolithApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class MonolithPaymentTest extends AbstractPaymentTest {
    @Autowired
    ApplicationContext context;

    /** Positive control for the payment host's absence check: here the same user beans really exist. */
    @Test
    void user_module_registers_its_auto_configuration_and_contract_bean() {
        assertEquals(1, context.getBeanNamesForType(UserAutoConfiguration.UserMarker.class).length);
        assertEquals(1, context.getBeanNamesForType(UserApi.class).length);
    }
}
