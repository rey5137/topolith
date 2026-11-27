package io.github.rey5137.topolith.samples.payment.service;

import java.security.Principal;

import io.github.rey5137.topolith.samples.payment.api.Tenants;
import io.github.rey5137.topolith.spring.boot.TenantContext;
import org.springframework.stereotype.Component;

// snippet:start sample-tenants
/** Adapts the sample's {@link Tenants} holder to the tenant a caller's calls carry (R-EXT-12). */
@Component
class SampleTenants implements TenantContext {
    @Override
    public String current() {
        return Tenants.current();
    }

    /** A real service checks {@code tenant} against {@code principal} here and throws to reject it. */
    @Override
    public Scope open(String tenant, Principal principal) {
        String previous = Tenants.current();
        Tenants.set(tenant);
        return () -> Tenants.set(previous);
    }
}
// snippet:end sample-tenants
