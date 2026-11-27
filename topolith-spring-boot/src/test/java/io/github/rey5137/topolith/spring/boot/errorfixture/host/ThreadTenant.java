package io.github.rey5137.topolith.spring.boot.errorfixture.host;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.rey5137.topolith.spring.boot.TenantContext;

/** A host's tenant holder on a thread local, which rejects the tenant {@code evil}, and what the provider saw. */
public final class ThreadTenant implements TenantContext {
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();
    /** Handler calls of the account module's {@code ctx} case. */
    public static final AtomicInteger HANDLED = new AtomicInteger();
    /** The principal name {@link #open} last received, or {@code anonymous}. */
    public static volatile String lastPrincipal;
    /** The module id {@link #open} last received. */
    public static volatile String lastModule;

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
    public Scope open(Request request) {
        lastPrincipal = request.principal() == null ? "anonymous" : request.principal().getName();
        lastModule = request.moduleId();
        String tenant = request.tenant();
        if ("evil".equals(tenant)) {
            throw new IllegalStateException("tenant secret message");
        }
        String previous = HOLDER.get();
        set(tenant);
        return () -> set(previous);
    }
}
