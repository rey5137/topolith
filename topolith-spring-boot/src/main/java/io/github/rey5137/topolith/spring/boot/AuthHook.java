package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.util.List;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * A module's one outgoing-auth hook, after every interceptor a customizer adds (R-EXT-13, D-92). A request that
 * already carries an {@code Authorization} the hook changes fails before it is sent with TL0036, so two credential
 * sources never pick a winner silently (INV-4).
 */
final class AuthHook implements ClientHttpRequestInterceptor {
    private final String module;
    private final ClientHttpRequestInterceptor hook;

    AuthHook(String module, ClientHttpRequestInterceptor hook) {
        this.module = module;
        this.hook = hook;
    }

    /** Adds {@code interceptor} before the hook in {@code interceptors}, or last when there is no hook yet. */
    static void insert(List<ClientHttpRequestInterceptor> interceptors, ClientHttpRequestInterceptor interceptor) {
        for (int i = 0; i < interceptors.size(); i++) {
            if (interceptors.get(i) instanceof AuthHook) {
                interceptors.add(i, interceptor);
                return;
            }
        }
        interceptors.add(interceptor);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        List<String> before = List.copyOf(request.getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION));
        if (before.isEmpty()) {
            return hook.intercept(request, body, execution);
        }
        return hook.intercept(request, body, (sent, sentBody) -> {
            if (!before.equals(sent.getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION))) {
                throw new TopolithException(new Diagnostic(TopolithCode.TL0036, "the outgoing-auth hook of module '"
                        + module + "' changed the Authorization header a client customizer had set; the call was "
                        + "not sent.", "Set credentials in either the customizer or the hook, not both."));
            }
            return execution.execute(sent, sentBody);
        });
    }
}
