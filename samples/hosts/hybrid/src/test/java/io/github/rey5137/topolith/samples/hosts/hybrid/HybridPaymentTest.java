package io.github.rey5137.topolith.samples.hosts.hybrid;

import io.github.rey5137.topolith.samples.payment.service.PaymentServiceApplication;
import io.github.rey5137.topolith.samples.payment.tests.AbstractPaymentTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts payment-service in this JVM (D-67). Its JAR is on the test classpath only for that; until M4.2 swaps in
 * containers, the payment module being present must leave no trace in the host context (INV-2).
 */
@SpringBootTest(classes = HybridApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HybridPaymentTest extends AbstractPaymentTest {
    private static final String PAYMENT_PACKAGE = "io.github.rey5137.topolith.samples.payment.";

    private static ConfigurableApplicationContext paymentService;

    @Autowired
    ConfigurableApplicationContext context;

    @DynamicPropertySource
    static void paymentServiceUrl(DynamicPropertyRegistry registry) {
        if (paymentService == null) {
            paymentService = new SpringApplicationBuilder(PaymentServiceApplication.class)
                    .properties("spring.config.location=classpath:/config/payment-service/",
                            "server.port=0",
                            "topolith.modules.user.mode=none")
                    .run();
        }
        String port = paymentService.getEnvironment().getProperty("local.server.port");
        registry.add("topolith.modules.payment.remote.url", () -> "http://localhost:" + port);
    }

    @AfterAll
    static void stopPaymentService() {
        if (paymentService != null) {
            paymentService.close();
            paymentService = null;
        }
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
    }
}
