package io.github.rey5137.topolith.spring.boot;

import java.security.Principal;

import io.github.rey5137.topolith.core.Incubating;

/**
 * The host's tenant, as Topolith's loopback and external calls carry it (R-EXT-12, D-91). A host exposes one bean of
 * this type, adapting the holder its application keeps the tenant in; with no bean no tenant crosses and with two
 * startup fails with TL0034. It uses JDK types only, so business code needs no Topolith type (INV-1).
 */
@Incubating
public interface TenantContext {
    /** The calling thread's tenant, or null when it has none. */
    String current();

    /**
     * Makes {@code tenant} current until the returned scope is closed, after checking it against {@code principal},
     * the request's principal (the {@code Authentication} under Spring Security, null when anonymous). A null
     * {@code tenant} makes no tenant current. Throw to reject a tenant the principal may not act for: the module route
     * then answers 403 without calling the provider and without the exception's message.
     */
    Scope open(String tenant, Principal principal);

    /** Restores the state before {@link #open}. */
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
