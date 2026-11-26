package io.github.rey5137.topolith.samples.payment.service;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

// snippet:start payment-service-main
/** Standalone payment service: only payment defaults to internal. */
@SpringBootApplication
public class PaymentServiceApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(PaymentServiceApplication.class)
                .properties("spring.config.location=classpath:/config/payment-service/")
                .run(args);
    }
}
// snippet:end payment-service-main
