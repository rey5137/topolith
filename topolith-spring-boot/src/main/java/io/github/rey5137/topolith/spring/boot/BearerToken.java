package io.github.rey5137.topolith.spring.boot;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/** Reads the calling thread's bearer token; loaded only when the resource-server classes are present (INV-7). */
final class BearerToken {
    private BearerToken() {
    }

    /** The token of the calling thread's {@code Authentication}, or null when it holds none (D-93). */
    static String current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof AbstractOAuth2TokenAuthenticationToken<?> token
                ? token.getToken().getTokenValue() : null;
    }
}
