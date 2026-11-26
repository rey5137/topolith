package io.github.rey5137.topolith.spring.boot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import io.github.rey5137.topolith.spring.boot.internal.FaultSequences;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
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
final class LoopbackEndpoints implements FaultSequences {
    private final Environment environment;
    private final Map<String, AtomicReference<UriBuilderFactory>> bases = new ConcurrentHashMap<>();
    private final Map<String, LoopbackFaults> faults = new ConcurrentHashMap<>();
    private volatile String root;

    LoopbackEndpoints(Environment environment) {
        this.environment = environment;
    }

    /** Installs module {@code id}'s base URL and the guarded request factory, with no injection. */
    void install(String id, RestClient.Builder builder, ClientHttpRequestFactory factory) {
        install(id, builder, factory, LoopbackFaults.Settings.NONE, LoopbackFaults.DEFAULT_READ_TIMEOUT);
    }

    /**
     * Installs module {@code id}'s base URL, the guarded request factory and its latency and fault injection (R-EXT-10)
     * in its group's client builder; a call that waits {@code readTimeout} fails as a read timeout.
     */
    synchronized void install(String id, RestClient.Builder builder, ClientHttpRequestFactory factory,
            LoopbackFaults.Settings settings, Duration readTimeout) {
        LoopbackFaults module = faults.computeIfAbsent(id, key -> new LoopbackFaults(settings));
        AtomicReference<UriBuilderFactory> base = bases.computeIfAbsent(id,
                key -> new AtomicReference<>(new DefaultUriBuilderFactory(base(root == null ? "http://localhost"
                        : root, key))));
        builder.uriBuilderFactory(new ExternalClientEndpoints.SwappableUriBuilderFactory(base))
                .requestFactory(new ListeningRequestFactory(id, factory));
        if (settings.latency().isPositive() || settings.rate() > 0) {
            builder.requestInterceptor(new Injection(module, readTimeout));
        }
    }

    @Override
    public void restart() {
        faults.values().forEach(LoopbackFaults::restart);
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

    /**
     * Latency and faults, in the loopback client only (R-EXT-10, D-78). A fault is the real transport failure, so the
     * caller's translation (R-EXT-04) maps it as it maps a real one.
     */
    private static final class Injection implements ClientHttpRequestInterceptor {
        private final LoopbackFaults faults;
        private final Duration readTimeout;

        Injection(LoopbackFaults faults, Duration readTimeout) {
            this.faults = faults;
            this.readTimeout = readTimeout;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            LoopbackFaults.Settings settings = faults.settings();
            LoopbackFaults.Kind kind = faults.next() ? settings.kind() : null;
            if (kind == LoopbackFaults.Kind.CONNECTION_FAILURE) {
                throw new ConnectException("injected connection failure");
            }
            if (kind == LoopbackFaults.Kind.UNAVAILABLE) {
                return new Unavailable();
            }
            ClientHttpResponse response = execution.execute(request, body);
            boolean timeout = kind == LoopbackFaults.Kind.TIMEOUT || settings.latency().compareTo(readTimeout) >= 0;
            Duration wait = timeout && settings.latency().compareTo(readTimeout) > 0 ? readTimeout
                    : settings.latency();
            try {
                if (wait.isPositive()) {
                    Thread.sleep(wait);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                response.close();
                throw new IOException("interrupted while delaying a loopback call", e);
            }
            if (timeout) {
                response.close();
                throw new SocketTimeoutException("Read timed out");
            }
            return response;
        }
    }

    /** A 503 with no body and no Topolith problem. */
    private static final class Unavailable implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }

        @Override
        public String getStatusText() {
            return HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase();
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void close() {
        }
    }
}
