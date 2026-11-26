package io.github.rey5137.topolith.samples.hosts.hybrid;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.samples.paymenttests.AbstractPaymentTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registers payment-service in a Consul container and reaches it through {@code remote.service-id} and Spring Cloud
 * LoadBalancer (D-42, D-69). The host keeps its fixed {@code url} default; only this test switches to discovery.
 */
@SpringBootTest(classes = HybridApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HybridConsulPaymentTest extends AbstractPaymentTest {
    private static final String SERVICE_ID = "payment-service";
    private static final int PAYMENT_PORT = PaymentServiceContainers.freePort();

    private static Network network;
    private static ConsulContainer consul;
    private static int consulPort;
    private static GenericContainer<?> paymentService;

    @Override
    protected Mode paymentMode() {
        return Mode.EXTERNAL;
    }

    @DynamicPropertySource
    static void consulProperties(DynamicPropertyRegistry registry) {
        if (consul == null) {
            network = Network.newNetwork();
            consul = new ConsulContainer("hashicorp/consul:1.20")
                    .withNetwork(network)
                    .withNetworkAliases("consul");
            consul.start();
            paymentService = PaymentServiceContainers.paymentService(PAYMENT_PORT)
                    .withNetwork(network)
                    .withEnv("SPRING_APPLICATION_NAME", SERVICE_ID)
                    .withEnv("SPRING_CLOUD_CONSUL_ENABLED", "true")
                    .withEnv("SPRING_CLOUD_CONSUL_HOST", "consul")
                    .withEnv("SPRING_CLOUD_CONSUL_PORT", "8500")
                    .withEnv("SPRING_CLOUD_CONSUL_DISCOVERY_HOSTNAME", "localhost")
                    .withEnv("SPRING_CLOUD_CONSUL_DISCOVERY_PORT", String.valueOf(PAYMENT_PORT))
                    .withEnv("SPRING_CLOUD_CONSUL_DISCOVERY_PREFER_IP_ADDRESS", "false")
                    // Consul cannot reach localhost:<host port> from inside its container, so no health check.
                    .withEnv("SPRING_CLOUD_CONSUL_DISCOVERY_REGISTER_HEALTH_CHECK", "false");
            paymentService.start();
            consulPort = consul.getMappedPort(8500);
            PaymentServiceContainers.awaitRegistration(consulPort);
        }
        int port = consulPort;
        registry.add("topolith.modules.payment.remote.service-id", () -> SERVICE_ID);
        registry.add("topolith.modules.payment.remote.url", () -> "");
        registry.add("spring.cloud.consul.enabled", () -> "true");
        registry.add("spring.cloud.consul.host", () -> "localhost");
        registry.add("spring.cloud.consul.port", () -> port);
        // The host consumes discovery on each call; only payment-service registers.
        registry.add("spring.cloud.service-registry.auto-registration.enabled", () -> "false");
        registry.add("spring.cloud.consul.discovery.catalog-services-watch.enabled", () -> "false");
    }

    @AfterAll
    static void stopContainers() {
        if (paymentService != null) {
            paymentService.stop();
            paymentService = null;
        }
        if (consul != null) {
            consul.stop();
            consul = null;
        }
        if (network != null) {
            network.close();
            network = null;
        }
    }

    @Test
    void ac_test_07_consul_discovery_reaches_the_registered_payment_service() {
        assertEquals("lb://payment-service/payment",
                environment.getProperty("spring.http.serviceclient.payment.base-url"));
        String catalog = PaymentServiceContainers.body(
                "http://localhost:" + consulPort + "/v1/catalog/service/" + SERVICE_ID);
        assertTrue(PaymentServiceContainers.containsField(catalog, "ServiceName", SERVICE_ID), catalog);
        assertTrue(PaymentServiceContainers.containsField(catalog, "ServiceAddress", "localhost"), catalog);
        assertTrue(PaymentServiceContainers.containsField(catalog, "ServicePort", String.valueOf(PAYMENT_PORT)),
                catalog);
    }
}
