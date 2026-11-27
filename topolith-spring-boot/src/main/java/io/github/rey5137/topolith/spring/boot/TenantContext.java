package io.github.rey5137.topolith.spring.boot;

import java.security.Principal;
import java.util.Objects;

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
     * Makes the request's tenant current until the returned scope is closed, after checking it against the request's
     * principal and the module it calls. A null {@link Request#tenant()} makes no tenant current. Throw to reject a
     * tenant the principal may not act for: the module route then answers 403 without calling the provider and
     * without the exception's message.
     */
    Scope open(Request request);

    /**
     * What a module route's request carries. Topolith constructs it; a host test builds one with {@link #of}. A later
     * field adds an accessor without breaking implementors.
     */
    final class Request {
        private final String tenant;
        private final Principal principal;
        private final String moduleId;

        private Request(String tenant, Principal principal, String moduleId) {
            this.tenant = tenant;
            this.principal = principal;
            this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        }

        /** A request for a host's test of its {@code TenantContext}. */
        public static Request of(String tenant, Principal principal, String moduleId) {
            return new Request(tenant, principal, moduleId);
        }

        /** The tenant the caller sent, or null when it sent none. */
        public String tenant() {
            return tenant;
        }

        /** The request's principal (the {@code Authentication} under Spring Security), null when anonymous. */
        public Principal principal() {
            return principal;
        }

        /** The id of the module whose route is called. */
        public String moduleId() {
            return moduleId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Request that && Objects.equals(tenant, that.tenant)
                    && Objects.equals(principal, that.principal) && moduleId.equals(that.moduleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenant, principal, moduleId);
        }

        /** Never prints the principal's credentials: its name only. */
        @Override
        public String toString() {
            return "TenantContext.Request[tenant=" + tenant + ", principal="
                    + (principal == null ? null : principal.getName()) + ", moduleId=" + moduleId + "]";
        }
    }

    /** Restores the state before {@link #open}. */
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
