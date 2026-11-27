package io.github.rey5137.topolith.spring.boot.errorfixture.host;

import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** What the account provider's {@code auth} case saw of its caller (R-EXT-13); loaded only when that case runs. */
public final class SeenAuth {
    /** Handler calls of the {@code auth} case. */
    public static final AtomicInteger HANDLED = new AtomicInteger();
    /** The {@code Authorization} header the provider's request carried, or null (internal mode: no request). */
    public static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    /** The provider thread's security context. */
    public static final AtomicReference<SecurityContext> CONTEXT = new AtomicReference<>();

    private SeenAuth() {
    }

    public static void clear() {
        HANDLED.set(0);
        AUTHORIZATION.set(null);
        CONTEXT.set(null);
    }

    /** The provider's {@code Authentication} as parity compares it: its name and its authorities (D-94). */
    public static String seen() {
        HANDLED.incrementAndGet();
        CONTEXT.set(SecurityContextHolder.getContext());
        AUTHORIZATION.set(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request
                ? request.getRequest().getHeader(HttpHeaders.AUTHORIZATION) : null);
        return describe(SecurityContextHolder.getContext().getAuthentication());
    }

    public static String describe(Authentication authentication) {
        if (authentication == null) {
            return "none";
        }
        TreeSet<String> authorities = new TreeSet<>();
        authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).forEach(authorities::add);
        return authentication.getName() + " " + authorities;
    }
}
