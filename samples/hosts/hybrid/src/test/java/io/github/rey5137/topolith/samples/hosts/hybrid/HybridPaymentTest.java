package io.github.rey5137.topolith.samples.hosts.hybrid;

import io.github.rey5137.topolith.samples.payment.tests.AbstractPaymentTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs payment-service as its Boot fat JAR in a container and reaches it over the fixed URL (D-69). The payment module
 * is not on the test classpath at all, so the host context can hold no trace of it (INV-2).
 */
@SpringBootTest(classes = HybridApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HybridPaymentTest extends AbstractPaymentTest {
    private static final String PAYMENT_PACKAGE = "io.github.rey5137.topolith.samples.payment.";
    private static final int PAYMENT_PORT = PaymentServiceContainers.freePort();

    private static GenericContainer<?> paymentService;

    @Autowired
    ConfigurableApplicationContext context;

    @DynamicPropertySource
    static void paymentServiceUrl(DynamicPropertyRegistry registry) {
        if (paymentService == null) {
            paymentService = PaymentServiceContainers.paymentService(PAYMENT_PORT);
            paymentService.start();
        }
        registry.add("topolith.modules.payment.remote.url", () -> "http://localhost:" + PAYMENT_PORT);
    }

    @AfterAll
    static void stopPaymentService() {
        if (paymentService != null) {
            paymentService.stop();
            paymentService = null;
        }
    }

    @Test
    void ac_test_07_fixed_url_reaches_the_payment_service_container() {
        assertTrue(paymentService.isRunning(), "payment-service container");
        assertEquals(PAYMENT_PORT, paymentService.getMappedPort(PaymentServiceContainers.PAYMENT_PORT));
        assertEquals("http://localhost:" + PAYMENT_PORT,
                environment.getProperty("topolith.modules.payment.remote.url"));
        assertEquals(200, PaymentServiceContainers.status(
                "http://localhost:" + PAYMENT_PORT + "/payment/.topolith/api-version"));
    }

    @Test
    void external_payment_leaves_no_payment_module_bean_in_the_host_context() {
        ConfigurableListableBeanFactory factory = context.getBeanFactory();
        for (String name : factory.getBeanDefinitionNames()) {
            Class<?> type = factory.getType(name, false);
            if (type != null) {
                assertTrue(!type.getName().startsWith(PAYMENT_PACKAGE)
                                || type.getName().startsWith(PAYMENT_PACKAGE + "api."),
                        "payment module bean '" + name + "' (" + type.getName() + ")");
            }
        }
        // The only payment-shaped bean is the contract client the framework builds from the descriptor.
        assertEquals(1, factory.getBeanNamesForType(io.github.rey5137.topolith.samples.payment.api.PaymentApi.class)
                .length);
        // The module JAR is not a test dependency, so even its classes are unloadable (D-67, D-69).
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.github.rey5137.topolith.samples.payment.PaymentConfiguration"));
    }
}
