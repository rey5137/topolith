package io.github.rey5137.topolith.spring.boot;

import java.util.List;

import io.github.rey5137.topolith.core.Incubating;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Matches a request under any exposed {@code /<id>/**} prefix, for the host's own
 * {@code http.securityMatcher(exposedRequestMatcher)} (D-46). It matches nothing when no module is exposed. A bean
 * only when Spring Security's web module is present.
 */
@Incubating
public final class ExposedRequestMatcher implements RequestMatcher {
    private final RequestMatcher delegate;

    public ExposedRequestMatcher(ExposedEndpoints endpoints) {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        List<RequestMatcher> matchers = endpoints.patterns().stream().map(paths::matcher)
                .map(RequestMatcher.class::cast).toList();
        this.delegate = matchers.isEmpty() ? request -> false : new OrRequestMatcher(matchers);
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        return delegate.matches(request);
    }
}
