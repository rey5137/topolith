package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilderFactory;

/**
 * Where each loopback module's client calls (R-MODE-06, D-76):
 * {@code http://<host>:<port><context-path><servlet-path>/<id>}, with the port of the started servlet server and the
 * path of Boot's {@code DispatcherServlet} (empty for {@code /}). {@code <host>} is {@code server.address}, an IPv6
 * address in brackets, or {@code localhost} when it is unset or a wildcard. Until the server listens, every request fails with a
 * {@link ConnectException}, which the caller's translation turns into a {@code connection failure} as for a remote
 * that is not listening (D-80). The client's settings are fixed at startup (D-77).
 */
final class LoopbackEndpoints {
    private final Environment environment;
    private final Map<String, AtomicReference<UriBuilderFactory>> bases = new ConcurrentHashMap<>();
    private volatile String root;

    LoopbackEndpoints(Environment environment) {
        this.environment = environment;
    }

    /** Installs module {@code id}'s base URL and the guarded request factory in its group's client builder. */
    synchronized void install(String id, RestClient.Builder builder, ClientHttpRequestFactory factory) {
        AtomicReference<UriBuilderFactory> base = bases.computeIfAbsent(id,
                key -> new AtomicReference<>(new DefaultUriBuilderFactory(base(root == null ? "http://localhost"
                        : root, key))));
        builder.uriBuilderFactory(new ExternalClientEndpoints.SwappableUriBuilderFactory(base))
                .requestFactory(new ListeningRequestFactory(id, factory));
    }

    /** Points every loopback client at the started server's local {@code port} and dispatcher servlet path. */
    synchronized void listen(int port, String servletPath) {
        root = "http://" + host() + ":" + port + path(environment.getProperty("server.servlet.context-path", ""))
                + path(servletPath == null ? "" : servletPath);
        bases.forEach((id, base) -> base.set(new DefaultUriBuilderFactory(base(root, id))));
    }

    /** The base URL module {@code id}'s client calls, or null before the server listens. */
    String baseUrl(String id) {
        String current = root;
        return current == null ? null : base(current, id);
    }

    private static String base(String root, String id) {
        return root + "/" + id;
    }

    private String host() {
        InetAddress address = Binder.get(environment).bind("server.address", InetAddress.class).orElse(null);
        if (address == null || address.isAnyLocalAddress()) {
            return "localhost";
        }
        String literal = address.getHostAddress();
        return address instanceof Inet6Address ? "[" + literal.replace("%", "%25") + "]" : literal;
    }

    /** A path segment with a leading and no trailing slash; empty for {@code /} or nothing. */
    private static String path(String value) {
        String path = value.trim().replaceAll("/+$", "");
        return path.isEmpty() || path.startsWith("/") ? path : "/" + path;
    }

    private final class ListeningRequestFactory implements ClientHttpRequestFactory {
        private final String id;
        private final ClientHttpRequestFactory target;

        ListeningRequestFactory(String id, ClientHttpRequestFactory target) {
            this.id = id;
            this.target = target;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
            if (root == null) {
                throw new ConnectException("loopback module '" + id + "' was called before the server listens");
            }
            return target.createRequest(uri, httpMethod);
        }
    }
}
