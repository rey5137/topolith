package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The provider half of {@link ContextPropagation}, around a module route's handler (R-EXT-12): {@link #open} makes
 * the sent values current and {@link #close} puts the thread back. Each dispatch that opens pushes its state on a
 * stack kept in the request, so a forward to another module route or an async re-dispatch restores the outer state.
 */
final class ContextRoute {
    private static final Logger LOG = LoggerFactory.getLogger(ContextRoute.class);
    private static final String STATES = ContextRoute.class.getName() + ".states";
    private static final String MDC_HEADER = ContextPropagation.MDC_PREFIX.toLowerCase(Locale.ROOT);
    private static final String TENANT_HEADER = ContextPropagation.TENANT_HEADER.toLowerCase(Locale.ROOT);
    private static final String CARRIER_HEADER = ContextPropagation.CARRIER_PREFIX.toLowerCase(Locale.ROOT);

    private final ContextPropagation context;
    // a client can send Topolith-Tenant on every request: only the first is an error in the log
    private final AtomicBoolean tenantMissingLogged = new AtomicBoolean();

    /** The restored values of one dispatch: the listed keys' previous MDC values (null when absent) and the scope. */
    private record State(Map<String, String> previous, TenantContext.Scope scope, List<ContextCarrier.Scope> carriers) {
    }

    ContextRoute(ContextPropagation context) {
        this.context = context;
    }

    /**
     * Before a module route's handler: puts the sent values and removes the listed keys the request does not carry.
     * False when it answered 400, 403 or 500 (TL0039) and the handler must not run.
     */
    boolean open(HttpServletRequest request, HttpServletResponse response, String moduleId) throws IOException {
        Map<String, String> sent = new LinkedHashMap<>();
        Map<String, String> carrierValues = new LinkedHashMap<>();
        String tenant = null;
        try {
            for (String name : Collections.list(request.getHeaderNames())) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith(MDC_HEADER)) {
                    String key = context.keysByLowerCase().get(lower.substring(MDC_HEADER.length()));
                    if (key != null) {
                        sent.put(key, ContextPropagation.decode(request.getHeader(name)));
                    }
                } else if (lower.startsWith(CARRIER_HEADER)) {
                    String key = lower.substring(CARRIER_HEADER.length());
                    if (context.carriers().containsKey(key)) {
                        carrierValues.put(key, ContextPropagation.decode(request.getHeader(name)));
                    }
                } else if (lower.equals(TENANT_HEADER)) {
                    tenant = ContextPropagation.decode(request.getHeader(name));
                }
            }
        } catch (IllegalArgumentException malformed) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return false;
        }
        TenantContext tenants = context.tenantContext();
        if (tenant != null && tenants == null) {
            Diagnostic problem = new Diagnostic(TopolithCode.TL0039, "a module route request carries "
                    + ContextPropagation.TENANT_HEADER + " but the host has no TenantContext bean, so the provider "
                    + "would run without its caller's tenant.", "Add a TenantContext bean to this host.");
            if (tenantMissingLogged.compareAndSet(false, true)) {
                LOG.error(problem.toString());
            } else {
                LOG.debug(problem.toString());
            }
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return false;
        }
        Map<String, String> previous = new LinkedHashMap<>();
        for (String key : context.keys()) {
            previous.put(key, MDC.get(key));
            put(key, sent.get(key));
        }
        TenantContext.Scope scope = null;
        if (tenants != null) {
            try {
                scope = tenants.open(TenantContext.Request.of(tenant, request.getUserPrincipal(), moduleId));
            } catch (RuntimeException rejected) {
                restore(previous);
                LOG.debug("The tenant context rejected the tenant of a module route request", rejected);
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
        }
        List<ContextCarrier.Scope> carrierScopes = new ArrayList<>();
        try {
            context.carriers().forEach((name, carrier) -> carrierScopes.add(carrier.open(carrierValues.get(name))));
        } catch (RuntimeException failure) {
            closeCarriers(carrierScopes);
            restore(previous);
            if (scope != null) {
                scope.close();
            }
            throw failure;
        }
        states(request, true).push(new State(previous, scope, carrierScopes));
        return true;
    }

    /**
     * When a dispatch completes, normally or not, or hands the request to async handling: back to the values before
     * the matching {@link #open}.
     */
    void close(HttpServletRequest request) {
        Deque<State> states = states(request, false);
        if (states == null || states.isEmpty()) {
            return;
        }
        State state = states.pop();
        if (states.isEmpty()) {
            request.removeAttribute(STATES);
        }
        try {
            closeCarriers(state.carriers());
        } finally {
            try {
                restore(state.previous());
            } finally {
                if (state.scope() != null) {
                    state.scope().close();
                }
            }
        }
    }

    private static void closeCarriers(List<ContextCarrier.Scope> scopes) {
        if (!scopes.isEmpty()) {
            ContextCarrier.Scope scope = scopes.remove(scopes.size() - 1);
            try {
                if (scope != null) {
                    scope.close();
                }
            } finally {
                closeCarriers(scopes);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Deque<State> states(HttpServletRequest request, boolean create) {
        Deque<State> states = (Deque<State>) request.getAttribute(STATES);
        if (states == null && create) {
            states = new ArrayDeque<>();
            request.setAttribute(STATES, states);
        }
        return states;
    }

    private static void restore(Map<String, String> previous) {
        previous.forEach(ContextRoute::put);
    }

    /** Sets the MDC {@code key} to {@code value}, or removes it when null. */
    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
