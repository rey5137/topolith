package io.github.rey5137.topolith.spring.boot.errorfixture.host;

import java.security.Principal;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.rey5137.topolith.spring.boot.TenantContext;

/** A host's tenant holder on a thread local, which rejects the tenant {@code evil}, and what the provider saw. */
public final class ThreadTenant implements TenantContext {
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();
    /** Handler calls of the account module's {@code ctx} case. */
    public static final AtomicInteger HANDLED = new AtomicInteger();
    /** The principal name {@link #open} last received, or {@code anonymous}. */
    public static volatile String lastPrincipal;

    public static String tenant() {
        return HOLDER.get();
    }

    public static void set(String tenant) {
        if (tenant == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(tenant);
        }
    }

    @Override
    public String current() {
        return HOLDER.get();
    }

    @Override
    public Scope open(String tenant, Principal principal) {
        lastPrincipal = principal == null ? "anonymous" : principal.getName();
        if ("evil".equals(tenant)) {
            throw new IllegalStateException("tenant secret message");
        }
        String previous = HOLDER.get();
        set(tenant);
        return () -> set(previous);
    }
}
