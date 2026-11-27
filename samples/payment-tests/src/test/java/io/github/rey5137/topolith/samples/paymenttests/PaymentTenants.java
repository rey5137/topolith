package io.github.rey5137.topolith.samples.paymenttests;

import java.security.Principal;

import io.github.rey5137.topolith.samples.payment.api.Tenants;
import io.github.rey5137.topolith.spring.boot.TenantContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** The tenant context a mode-matrix run's host exposes, adapting the sample's {@link Tenants} holder (R-EXT-12). */
@TestConfiguration(proxyBeanMethods = false)
public class PaymentTenants {
    @Bean
    TenantContext paymentTenantContext() {
        return new TenantContext() {
            @Override
            public String current() {
                return Tenants.current();
            }

            @Override
            public Scope open(String tenant, Principal principal) {
                String previous = Tenants.current();
                Tenants.set(tenant);
                return () -> Tenants.set(previous);
            }
        };
    }
}
