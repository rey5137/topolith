package io.github.rey5137.topolith.spring.boot.fixture.billing;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class BillingAutoConfiguration {
    @Bean("billingMarker")
    Object marker() {
        return new Object();
    }
}
