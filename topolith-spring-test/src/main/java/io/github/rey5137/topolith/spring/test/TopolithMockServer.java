package io.github.rey5137.topolith.spring.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.Incubating;
import org.springframework.util.ClassUtils;

/** A local HTTP server serving stubs below a module's fixed prefix. */
@Incubating
public final class TopolithMockServer implements AutoCloseable {
    private final String id;
    private final HttpServer server;
    private final Map<String, Response> stubs = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    /** Starts a server for the named module on an ephemeral loopback port. */
    public TopolithMockServer(String id) {
        this.id = id;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The base URL, without the module prefix. */
    public URI url() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

    /** Stubs a method and path relative to the module prefix with a JSON response. */
    public void stub(String method, String path, int status, String body) {
        stubs.put(key(method, path), new Response(status, body, "application/json"));
    }

    /**
     * Stubs a declared contract exception in the provider's problem format, with the status the provider derives: the
     * exception's merged {@code @ResponseStatus}, else 500, and a non-error status becomes 500.
     *
     * @throws IllegalArgumentException for a {@code ModuleUnavailableException}; use {@link #unavailable}
     */
    public void problem(String method, String path, Class<? extends Exception> exceptionType, String message) {
        rejectUnavailable(exceptionType);
        write(method, path, derivedStatus(exceptionType), exceptionType.getName(), message, id);
    }

    /**
     * Stubs a declared contract exception with an explicit error status.
     *
     * @throws IllegalArgumentException when {@code status} is not 4xx or 5xx, when the exception type is annotated
     *         with another status (spring-web present), or for a {@code ModuleUnavailableException}
     */
    public void problem(String method, String path, int status, Class<? extends Exception> exceptionType,
            String message) {
        rejectUnavailable(exceptionType);
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("status " + status + " is not an error status; the provider sends 500");
        }
        if (webPresent() && Status.annotated(exceptionType) != null && derivedStatus(exceptionType) != status) {
            throw new IllegalArgumentException("status " + status + " differs from " + derivedStatus(exceptionType)
                    + ", which the provider derives from @ResponseStatus on " + exceptionType.getName());
        }
        write(method, path, status, exceptionType.getName(), message, id);
    }

    private static void rejectUnavailable(Class<?> exceptionType) {
        if (ModuleUnavailableException.class.isAssignableFrom(exceptionType)) {
            throw new IllegalArgumentException(exceptionType.getName() + " is written by the provider as a 503 for"
                    + " the downstream module; stub it with unavailable(...)");
        }
    }

    private static boolean webPresent() {
        return present("org.springframework.web.bind.annotation.ResponseStatus");
    }

    private static boolean present(String className) {
        return ClassUtils.isPresent(className, TopolithMockServer.class.getClassLoader());
    }

    private static int derivedStatus(Class<?> exceptionType) {
        if (!webPresent()) { return 500; }
        Integer annotated = Status.annotated(exceptionType);
        return annotated == null || annotated < 400 || annotated > 599 ? 500 : annotated;
    }

    /** Stubs the 503 the provider writes for an unreachable downstream module, with its fixed detail. */
    public void unavailable(String method, String path, String downstreamModuleId) {
        write(method, path, 503, ModuleUnavailableException.class.getName(),
                "module '" + downstreamModuleId + "' could not be reached (unavailable downstream)",
                downstreamModuleId);
    }

    private void write(String method, String path, int status, String exception, String detail, String module) {
        String title = present("org.springframework.http.HttpStatus")
                ? Status.title(status) : status == 500 ? "Internal Server Error" : null;
        String body = "{\"type\":\"urn:topolith:problem:contract-exception\","
                + (title == null ? "" : "\"title\":" + json(title) + ",") + "\"status\":" + status
                + ",\"detail\":" + json(detail) + ",\"exception\":" + json(exception)
                + ",\"module\":" + json(module) + "}";
        stubs.put(key(method, path), new Response(status, body, "application/problem+json"));
    }

    /** Isolated and reached only when spring-web is present, so the annotation never loads at server start. */
    private static final class Status {
        /** The status's reason phrase, as the provider's problem converter adds it; null for a non-standard status. */
        static String title(int status) {
            var resolved = org.springframework.http.HttpStatus.resolve(status);
            return resolved == null ? null : resolved.getReasonPhrase();
        }

        /** The merged {@code @ResponseStatus} code, or null when the type has none. */
        static Integer annotated(Class<?> type) {
            var annotated = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(type,
                    org.springframework.web.bind.annotation.ResponseStatus.class);
            return annotated == null ? null : annotated.code().value();
        }
    }

    /**
     * Returns a snapshot of the requests made to this module, in arrival order, with their full paths, except the
     * readiness check's {@code GET /<id>/.topolith/api-version} (R-VAL-04, D-65), which is served from stubs but never
     * recorded.
     */
    public List<Request> requests() { return List.copyOf(requests); }

    /** Clears stubs and recorded requests. */
    public void reset() { stubs.clear(); requests.clear(); }

    /** A recorded HTTP request. */
    @Incubating
    public record Request(String method, String path, String body) { }

    private record Response(int status, String body, String contentType) { }

    /** The provider's version endpoint (R-VAL-04), copied from the boot module and guarded by a test. */
    static final String VERSION_PATH = "/.topolith/api-version";

    private static String key(String method, String path) {
        return method.toUpperCase(java.util.Locale.ROOT) + " " + path;
    }

    private static String json(String value) {
        if (value == null) { return "null"; }
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 32) { result.append(String.format("\\u%04x", (int) c)); }
                    else { result.append(c); }
                }
            }
        }
        return result.append('"').toString();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String prefix = "/" + id;
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // the readiness check can arrive at any time (ready event, D-50 refresh), so it is never recorded (D-65)
            if (!("GET".equalsIgnoreCase(method) && path.equals(prefix + VERSION_PATH))) {
                requests.add(new Request(method, path, body));
            }
            Response response = path.startsWith(prefix + "/") || path.equals(prefix)
                    ? stubs.get(key(method, path.substring(prefix.length()))) : null;
            if (response == null) { exchange.sendResponseHeaders(404, -1); return; }
            exchange.getResponseHeaders().set("Topolith-Module", id);
            if (response.body() == null || response.body().isEmpty()) {
                exchange.sendResponseHeaders(response.status(), -1);
                return;
            }
            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), payload.length);
            exchange.getResponseBody().write(payload);
        }
    }

    /** Stops the server. */
    @Override
    public void close() { server.stop(0); }
}
