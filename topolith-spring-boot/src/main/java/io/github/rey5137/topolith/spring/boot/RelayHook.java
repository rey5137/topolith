package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * The {@code relay} hook (D-93): sends the bearer token the calling thread's {@code Authentication} held, which
 * {@link BearerToken} read into a request attribute on the calling thread, since the exchange may run on another one
 * (INV-6). With no token it sends nothing and nothing fails.
 */
final class RelayHook implements ClientHttpRequestInterceptor {
    static final String TOKEN = RelayHook.class.getName() + ".token";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (request.getAttributes().get(TOKEN) instanceof String token) {
            request.getHeaders().setBearerAuth(token);
        }
        return execution.execute(request, body);
    }
}
