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
import io.github.rey5137.topolith.core.Incubating;

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

    /** Stubs a declared contract exception in the provider's problem format. */
    public void problem(String method, String path, int status, Class<? extends RuntimeException> exceptionType,
            String message) {
        String body = "{\"type\":\"urn:topolith:problem:contract-exception\",\"status\":" + status
                + ",\"detail\":" + json(message) + ",\"exception\":" + json(exceptionType.getName())
                + ",\"module\":" + json(id) + "}";
        stubs.put(key(method, path), new Response(status, body, "application/problem+json"));
    }

    /** Returns a snapshot of requests including their full paths. */
    public List<Request> requests() { return List.copyOf(requests); }

    /** Clears stubs and recorded requests. */
    public void reset() { stubs.clear(); requests.clear(); }

    /** A recorded HTTP request. */
    @Incubating
    public record Request(String method, String path, String body) { }

    private record Response(int status, String body, String contentType) { }

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
            requests.add(new Request(method, path,
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String prefix = "/" + id;
            Response response = path.startsWith(prefix + "/") || path.equals(prefix)
                    ? stubs.get(key(method, path.substring(prefix.length()))) : null;
            if (response == null) { exchange.sendResponseHeaders(404, -1); return; }
            if (response.body() == null || response.body().isEmpty()) {
                exchange.sendResponseHeaders(response.status(), -1);
                return;
            }
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
        }
    }

    /** Stops the server. */
    @Override
    public void close() { server.stop(0); }
}
