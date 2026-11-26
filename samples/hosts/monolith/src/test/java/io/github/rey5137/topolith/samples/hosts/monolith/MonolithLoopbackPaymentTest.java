package io.github.rey5137.topolith.samples.hosts.monolith;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.samples.payment.api.PaymentNotFoundException;
import io.github.rey5137.topolith.samples.paymenttests.AbstractPaymentTest;
import io.github.rey5137.topolith.spring.test.TopolithModuleMode;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The loopback column of the payment mode matrix: payment runs in this host, every call over HTTP (R-TEST-05). */
@Import(MonolithLoopbackPaymentTest.PaymentRequestCounter.class)
// snippet:start monolith-loopback-payment-test
@SpringBootTest(classes = MonolithApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:monolith-loopback;DB_CLOSE_DELAY=-1")
@AutoConfigureTestRestTemplate
@TopolithModuleMode(id = "payment", mode = Mode.LOOPBACK)
class MonolithLoopbackPaymentTest extends AbstractPaymentTest {
    @Override
    protected Mode paymentMode() {
        return Mode.LOOPBACK;
    }
// snippet:end monolith-loopback-payment-test

    @Autowired
    AtomicInteger paymentRequests;

    /** The test sits outside the payment module, so its calls reach payment's routes on this server (D-81). */
    @Test
    void ac_test_14_loopback_calls_from_outside_payment_reach_its_routes_over_http() {
        int before = paymentRequests.get();
        assertThrows(PaymentNotFoundException.class, () -> paymentApi.find("missing-" + UUID.randomUUID()));
        assertEquals(before + 1, paymentRequests.get());
    }

    /** Counts the requests the server receives under payment's {@code /payment} prefix. */
    @TestConfiguration(proxyBeanMethods = false)
    static class PaymentRequestCounter {
        @Bean
        AtomicInteger paymentRequests() {
            return new AtomicInteger();
        }

        @Bean
        FilterRegistrationBean<Filter> paymentRequestFilter(AtomicInteger paymentRequests) {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                paymentRequests.incrementAndGet();
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/payment/*");
            return registration;
        }
    }
}
