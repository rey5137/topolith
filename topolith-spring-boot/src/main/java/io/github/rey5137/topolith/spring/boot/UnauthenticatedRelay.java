package io.github.rey5137.topolith.spring.boot;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Optional Spring Security check for a bearer that reached a module without authentication. */
final class UnauthenticatedRelay {
    private static final Logger LOG = LoggerFactory.getLogger(UnauthenticatedRelay.class);
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    void check(HttpServletRequest request, String module) {
        String authorization = request.getHeader("Authorization");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                && (authentication == null || !authentication.isAuthenticated()
                        || authentication instanceof AnonymousAuthenticationToken) && warned.add(module)) {
            LOG.warn(new Diagnostic(TopolithCode.TL0046, "module '" + module
                    + "' received a bearer token without authentication.",
                    "Configure the host as a resource server for the caller's tokens.").toString());
        }
    }
}
