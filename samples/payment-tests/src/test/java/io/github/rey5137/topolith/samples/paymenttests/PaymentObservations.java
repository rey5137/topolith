package io.github.rey5137.topolith.samples.paymenttests;

import io.micrometer.observation.tck.TestObservationRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** The observation registry a mode-matrix run records its contract calls in; import it from the host's test. */
@TestConfiguration(proxyBeanMethods = false)
public class PaymentObservations {
    @Bean
    TestObservationRegistry testObservationRegistry() {
        return TestObservationRegistry.create();
    }
}
