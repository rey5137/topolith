package io.github.rey5137.topolith.samples.hosts.paymenthost;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The payment host: payment internal, user none. */
@SpringBootApplication
public class PaymentHostApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentHostApplication.class, args);
    }
}
