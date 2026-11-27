package io.github.rey5137.topolith.spring.cloud;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;
import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceModules;
import io.github.rey5137.topolith.spring.boot.observation.ModuleCallContext;
import io.github.rey5137.topolith.spring.cloud.fixture.host.Host;
import io.github.rey5137.topolith.spring.cloud.fixture.host.OrderCaller;
import io.github.rey5137.topolith.spring.cloud.fixture.host.OrderHost;
import io.github.rey5137.topolith.spring.cloud.fixture.ledger.LedgerHttp;
import io.github.rey5137.topolith.spring.cloud.fixture.ledger.api.LedgerContract;
import io.github.rey5137.topolith.spring.cloud.fixture.orders.OrderController;
import io.github.rey5137.topolith.spring.cloud.fixture.orders.api.OrderContract;
import io.github.rey5137.topolith.spring.cloud.fixture.orders.api.OrderRejectedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.ConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.client.circuitbreaker.httpservice.HttpServiceFallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M7.3: per-group retry and circuit breaker (R-EXT-14, R-EXT-15, D-100 to D-105). */
class ResilienceTest {
    private static final String PACKAGE = "io.github.rey5137.topolith.spring.cloud.fixture";
    private static final String CONTRACT = OrderContract.class.getName();
    private static final String PROBLEM = "urn:topolith:problem:contract-exception";
    private static final String R = "topolith.modules.orders.remote.";

    @TempDir
    Path root;

    /** A scripted reply of the stub provider. */
    record Reply(int status, String type, String body, long delay) {
        static Reply status(int status) {
            return new Reply(status, "text/plain", "x", 0);
        }

        static Reply ok() {
            return new Reply(200, "text/plain", "ok", 0);
        }

        static Reply slow(long millis) {
            return new Reply(200, "text/plain", "late", millis);
        }

        static Reply problem(int status, String exception, String module) {
            return new Reply(status, "application/problem+json", "{\"type\":\"" + PROBLEM + "\",\"status\":" + status
                    + ",\"detail\":\"d\",\"exception\":\"" + exception + "\",\"module\":\"" + module + "\"}", 0);
        }
    }

    /** A provider that answers each path from its script, the last reply repeating, and counts what reached it. */
    static final class Stub implements AutoCloseable {
        final HttpServer server;
        final Map<String, List<Reply>> script = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        final Map<String, List<Map<String, List<String>>>> headers = new ConcurrentHashMap<>();
        /** Runs when the nth request of a path arrives, before it is answered: a test holds a call there. */
        final Map<String, java.util.function.IntConsumer> arrivals = new ConcurrentHashMap<>();

        Stub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                int n = counts.computeIfAbsent(path, key -> new AtomicInteger()).getAndIncrement();
                headers.computeIfAbsent(path, key -> new CopyOnWriteArrayList<>())
                        .add(new LinkedHashMap<>(exchange.getRequestHeaders()));
                java.util.function.IntConsumer arrival = arrivals.get(path);
                if (arrival != null) {
                    arrival.accept(n);
                }
                List<Reply> replies = script.getOrDefault(path, List.of(Reply.ok()));
                Reply reply = replies.get(Math.min(n, replies.size() - 1));
                try {
                    Thread.sleep(reply.delay());
                    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", reply.type());
                    exchange.sendResponseHeaders(reply.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException | InterruptedException e) {
                    // the client gave up
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        Stub on(String path, Reply... replies) {
            script.put("/orders" + path, List.of(replies));
            return this;
        }

        Stub onArrival(String path, java.util.function.IntConsumer hook) {
            arrivals.put("/orders" + path, hook);
            return this;
        }

        int count(String path) {
            AtomicInteger count = counts.get("/orders" + path);
            return count == null ? 0 : count.get();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Accepts connections and closes them at once: a connection failure after the connect. */
    static final class Dropper implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0);
        final AtomicInteger accepted = new AtomicInteger();

        Dropper() throws IOException {
            Thread thread = new Thread(() -> {
                try {
                    while (true) {
                        Socket client = socket.accept();
                        accepted.incrementAndGet();
                        client.close();
                    }
                } catch (IOException closed) {
                    // done
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Observed {
        static final List<Observation.Context> STOPPED = new CopyOnWriteArrayList<>();
        static final List<String> THREADS = new CopyOnWriteArrayList<>();

        @Bean
        ObservationRegistry registry() {
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }

                @Override
                public void onStop(Observation.Context context) {
                    STOPPED.add(context);
                    THREADS.add(context.getName() + "@" + Thread.currentThread().getName());
                }
            });
            return registry;
        }
    }

    /** Topolith's own breaker for module orders must not be configured by a host customizer. */
    @Configuration(proxyBeanMethods = false)
    static class ConfiguresOrders {
        @Bean
        Customizer<Resilience4JCircuitBreakerFactory> configures() {
            return factory -> factory.configure(builder -> builder.build(), "orders");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConfiguresDefault {
        @Bean
        Customizer<Resilience4JCircuitBreakerFactory> configures() {
            return factory -> factory.configureDefault(id -> new org.springframework.cloud.circuitbreaker
                    .resilience4j.Resilience4JConfigBuilder(id).circuitBreakerConfig(io.github.resilience4j
                            .circuitbreaker.CircuitBreakerConfig.custom().slidingWindowSize(2).build())
                    .timeLimiterConfig(io.github.resilience4j.timelimiter.TimeLimiterConfig.custom()
                            .timeoutDuration(java.time.Duration.ofMillis(10)).build()).build());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RegistersOrders {
        @Bean
        Customizer<Resilience4JCircuitBreakerFactory> registers() {
            return factory -> factory.getCircuitBreakerRegistry().circuitBreaker("orders");
        }
    }

    /** A factory of an unsupported implementation. */
    static class OtherFactory extends CircuitBreakerFactory<Object, ConfigBuilder<Object>> {
        @Override
        public org.springframework.cloud.client.circuitbreaker.CircuitBreaker create(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected ConfigBuilder<Object> configBuilder(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void configureDefault(java.util.function.Function<String, Object> defaultConfiguration) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondFactory {
        @Bean
        OtherFactory other() {
            return new OtherFactory();
        }
    }

    static class Fallback {
    }

    @Configuration(proxyBeanMethods = false)
    @HttpServiceFallback(value = Fallback.class, group = "orders")
    static class FallsBack {
    }

    /** A fallback with no group and no service: the default for every group. */
    @Configuration(proxyBeanMethods = false)
    @HttpServiceFallback(Fallback.class)
    static class FallsBackEverywhere {
    }

    @BeforeEach
    void descriptors() throws IOException {
        List<String> idempotent = List.of(CONTRACT + "#missing", CONTRACT + "#read", CONTRACT + "#reject",
                CONTRACT + "#replace");
        String api = PACKAGE + ".orders.api";
        write("apis", "orders", out -> Descriptors.write(ApiDescriptor.builder("orders").apiPackages(List.of(api))
                .apiVersion("1.0.0").provides(List.of(CONTRACT)).idempotent(idempotent).build(), out));
        write("modules", "orders", out -> Descriptors.write(ModuleDescriptor.builder("orders")
                .packageName(PACKAGE + ".orders").apiPackages(List.of(api)).apiVersion("1.0.0")
                .provides(List.of(CONTRACT)).idempotent(idempotent).build(), out));
        write("apis", "payment", out -> Descriptors.write(ApiDescriptor.builder("payment")
                .apiPackages(List.of(PACKAGE + ".payment.api")).apiVersion("1.0.0").provides(List.of()).build(), out));
        write("modules", "payment", out -> Descriptors.write(ModuleDescriptor.builder("payment")
                .packageName(PACKAGE + ".payment").apiPackages(List.of(PACKAGE + ".payment.api"))
                .apiVersion("1.0.0").provides(List.of()).build(), out));
    }

    private void ledger() throws IOException {
        String contract = LedgerContract.class.getName();
        String api = PACKAGE + ".ledger.api";
        write("apis", "ledger", out -> Descriptors.write(ApiDescriptor.builder("ledger").apiPackages(List.of(api))
                .apiVersion("1.0.0").provides(List.of(contract)).idempotent(List.of(contract + "#total")).build(),
                out));
        write("modules", "ledger", out -> Descriptors.write(ModuleDescriptor.builder("ledger")
                .packageName(PACKAGE + ".ledger").apiPackages(List.of(api)).apiVersion("1.0.0")
                .provides(List.of(contract)).idempotent(List.of(contract + "#total")).build(), out));
    }

    private interface Body {
        void write(Writer out) throws IOException;
    }

    private void write(String kind, String id, Body body) throws IOException {
        Path file = root.resolve("META-INF/topolith/" + kind + "/" + id + ".properties");
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            body.write(out);
        }
    }

    private static String[] props(String url, String... more) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("topolith.modules.orders.mode", "external");
        values.put("topolith.modules.payment.mode", "none");
        values.put(R + "url", url);
        values.put(R + "read-timeout", "300ms");
        values.put(R + "retry.initial-backoff", "1ms");
        values.put(R + "retry.max-backoff", "5ms");
        for (String pair : more) {
            values.put(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
        }
        return values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    private void external(String[] properties, Consumer<AssertableApplicationContext> check, Class<?>... extra)
            throws Exception {
        List<Class<?>> classes = new ArrayList<>(List.of(Host.class));
        classes.addAll(List.of(extra));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()},
                ResilienceTest.class.getClassLoader())) {
            new ApplicationContextRunner().withClassLoader(loader)
                    .withUserConfiguration(classes.toArray(Class<?>[]::new)).withPropertyValues(properties)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        check.accept(context);
                    });
        }
    }

    private void startupFails(String[] properties, TopolithCode code, String text, Class<?>... extra)
            throws Exception {
        List<Class<?>> classes = new ArrayList<>(List.of(Host.class));
        classes.addAll(List.of(extra));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()},
                ResilienceTest.class.getClassLoader())) {
            new ApplicationContextRunner().withClassLoader(loader)
                    .withUserConfiguration(classes.toArray(Class<?>[]::new)).withPropertyValues(properties)
                    .run(context -> {
                        Throwable cause = context.getStartupFailure();
                        while (cause != null && !(cause instanceof TopolithException)) {
                            cause = cause.getCause();
                        }
                        TopolithException failure = assertInstanceOf(TopolithException.class, cause,
                                String.valueOf(context.getStartupFailure()));
                        assertEquals(List.of(code), failure.diagnostics().stream().map(d -> d.code()).distinct()
                                .toList(), failure.getMessage());
                        assertTrue(failure.getMessage().contains(text), failure.getMessage());
                    });
        }
    }

    private static String kind(ModuleUnavailableException e) {
        return e.getMessage().replaceAll(".*\\((.*)\\)", "$1");
    }

    private static CircuitBreaker breaker(AssertableApplicationContext context) {
        return context.getBean(CircuitBreakerRegistry.class).find("orders").orElseThrow();
    }

    @Test
    void ac_ext_62_a_method_not_declared_idempotent_is_never_retried_whatever_its_http_method() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/peek", Reply.status(503)).on("/save", Reply.status(503)).on("/create", Reply.status(503))
                    .on("/remove", Reply.status(503));
            external(props(stub.url(), R + "retry.max-attempts=3"), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                for (Runnable call : List.<Runnable>of(orders::peek, orders::save, orders::create, orders::remove)) {
                    ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class, call::run);
                    assertEquals("status 503", kind(e));
                }
                assertEquals(List.of(1, 1, 1, 1), List.of(stub.count("/peek"), stub.count("/save"),
                        stub.count("/create"), stub.count("/remove")));
            });
        }
    }

    @Test
    void ac_ext_62_a_timeout_and_a_dropped_connection_make_one_attempt_for_a_method_not_declared_idempotent()
            throws Exception {
        try (Stub stub = new Stub(); Dropper dropper = new Dropper()) {
            stub.on("/save", Reply.slow(1000));
            external(props(stub.url(), R + "retry.max-attempts=3"), context -> {
                ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                        context.getBean(OrderContract.class)::save);
                assertEquals("timeout", kind(e));
                assertEquals(1, stub.count("/save"));
            });
            external(props("http://127.0.0.1:" + dropper.socket.getLocalPort(), R + "retry.max-attempts=3"),
                    context -> {
                        assertThrows(ModuleUnavailableException.class, context.getBean(OrderContract.class)::save);
                        assertEquals(1, dropper.accepted.get());
                    });
        }
    }

    @Test
    void ac_ext_69_safe_methods_are_retried_with_the_opt_in_and_the_others_are_not() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/peek", Reply.status(503), Reply.status(503), Reply.ok()).on("/save", Reply.status(503))
                    .on("/create", Reply.status(503)).on("/remove", Reply.status(503));
            external(props(stub.url(), R + "retry.max-attempts=3", R + "retry.safe-methods-idempotent=true"),
                    context -> {
                        OrderContract orders = context.getBean(OrderContract.class);
                        assertEquals("ok", orders.peek());
                        assertEquals(3, stub.count("/peek"));
                        assertThrows(ModuleUnavailableException.class, orders::save);
                        assertThrows(ModuleUnavailableException.class, orders::create);
                        assertThrows(ModuleUnavailableException.class, orders::remove);
                        assertEquals(List.of(1, 1, 1), List.of(stub.count("/save"), stub.count("/create"),
                                stub.count("/remove")));
                    });
        }
    }

    @Test
    void ac_ext_69_the_http_method_is_read_from_the_separate_interface_and_the_descriptor_from_the_contract()
            throws Exception {
        ledger();
        try (Stub stub = new Stub()) {
            for (String path : List.of("/total", "/peek", "/put")) {
                stub.script.put("/ledger" + path, List.of(Reply.status(503), Reply.ok()));
            }
            String mapping = "topolith.modules.ledger.remote.http-interface." + LedgerContract.class.getName()
                    + "=" + LedgerHttp.class.getName();
            String[] common = {"topolith.modules.ledger.mode=external", "topolith.modules.orders.mode=none",
                    "topolith.modules.payment.mode=none", mapping, "topolith.modules.ledger.remote.url=" + stub.url(),
                    "topolith.modules.ledger.remote.retry.max-attempts=2",
                    "topolith.modules.ledger.remote.retry.initial-backoff=1ms",
                    "topolith.modules.ledger.remote.retry.max-backoff=5ms"};
            external(common, context -> {
                LedgerContract ledger = context.getBean(LedgerContract.class);
                // declared on the contract, whose proxy is the separate interface
                assertEquals("ok", ledger.total());
                assertEquals(2, stub.counts.get("/ledger/total").get());
                assertThrows(ModuleUnavailableException.class, ledger::peek);
                assertEquals(1, stub.counts.get("/ledger/peek").get());
            });
            String[] safe = java.util.Arrays.copyOf(common, common.length + 1);
            safe[common.length] = "topolith.modules.ledger.remote.retry.safe-methods-idempotent=true";
            stub.counts.clear();
            external(safe, context -> {
                LedgerContract ledger = context.getBean(LedgerContract.class);
                assertEquals("ok", ledger.peek());
                assertEquals(2, stub.counts.get("/ledger/peek").get());
                assertThrows(ModuleUnavailableException.class, ledger::put);
                assertEquals(1, stub.counts.get("/ledger/put").get());
            });
        }
    }

    @Test
    void ac_ext_63_an_idempotent_method_is_retried_until_a_retry_succeeds() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(502), Reply.status(503), Reply.ok());
            external(props(stub.url(), R + "retry.max-attempts=3"), context -> {
                assertEquals("ok", context.getBean(OrderContract.class).read());
                assertEquals(3, stub.count("/read"));
            });
        }
    }

    @Test
    void ac_ext_63_a_504_and_a_timeout_and_a_dropped_connection_are_retried_until_the_attempts_run_out()
            throws Exception {
        try (Stub stub = new Stub(); Dropper dropper = new Dropper()) {
            stub.on("/read", Reply.status(504)).on("/replace", Reply.slow(1000));
            external(props(stub.url(), R + "retry.max-attempts=3"), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                assertEquals("status 504", kind(assertThrows(ModuleUnavailableException.class, orders::read)));
                assertEquals(3, stub.count("/read"));
                assertEquals("timeout", kind(assertThrows(ModuleUnavailableException.class, orders::replace)));
                assertEquals(3, stub.count("/replace"));
            });
            external(props("http://127.0.0.1:" + dropper.socket.getLocalPort(), R + "retry.max-attempts=4"),
                    context -> {
                        ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                                context.getBean(OrderContract.class)::replace);
                        assertEquals("connection failure", kind(e));
                        assertEquals(4, dropper.accepted.get());
                    });
        }
    }

    @Test
    void ac_ext_63_the_wait_between_attempts_grows_by_the_multiplier_up_to_the_maximum() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(503));
            external(props(stub.url(), R + "retry.max-attempts=4", R + "retry.initial-backoff=100ms",
                    R + "retry.max-backoff=150ms", R + "retry.multiplier=2"), context -> {
                        long start = System.nanoTime();
                        assertThrows(ModuleUnavailableException.class, context.getBean(OrderContract.class)::read);
                        long millis = (System.nanoTime() - start) / 1_000_000;
                        // 100 + 150 + 150, not 100 + 200 + 400
                        assertTrue(millis >= 390 && millis < 650, "waited " + millis);
                        assertEquals(4, stub.count("/read"));
                    });
        }
    }

    @Test
    void ac_ext_63_one_attempt_when_max_attempts_is_unset_or_one_even_with_backoff_keys() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(503));
            external(props(stub.url()), context -> {
                assertThrows(ModuleUnavailableException.class, context.getBean(OrderContract.class)::read);
                assertEquals(1, stub.count("/read"));
            });
            external(props(stub.url(), R + "retry.max-attempts=1"), context -> {
                assertThrows(ModuleUnavailableException.class, context.getBean(OrderContract.class)::read);
                assertEquals(2, stub.count("/read"));
            });
        }
    }

    @Test
    void ac_ext_64_an_idempotent_call_is_not_retried_on_any_other_outcome() throws Exception {
        String account = OrderRejectedException.class.getName();
        try (Stub stub = new Stub()) {
            stub.on("/reject", Reply.problem(500, account, "orders"))
                    .on("/missing", Reply.status(404))
                    .on("/read", Reply.problem(500, ModuleUnavailableException.class.getName(), "payment"))
                    .on("/replace", Reply.problem(503, "com.example.Unknown", "orders"));
            external(props(stub.url(), R + "retry.max-attempts=3"), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                assertThrows(OrderRejectedException.class, orders::reject);
                assertEquals(1, stub.count("/reject"));
                assertThrows(ModuleUnavailableException.class, orders::missing);
                assertEquals(1, stub.count("/missing"));
                ModuleUnavailableException downstream = assertThrows(ModuleUnavailableException.class, orders::read);
                assertEquals("payment", downstream.moduleId());
                assertEquals(1, stub.count("/read"));
                assertThrows(org.springframework.web.client.RestClientResponseException.class, orders::replace);
                assertEquals(1, stub.count("/replace"));
            });
        }
    }

    @Test
    void ac_ext_64_a_401_a_429_and_a_500_without_a_problem_are_not_retried() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(401)).on("/replace", Reply.status(429)).on("/reject", Reply.status(500));
            external(props(stub.url(), R + "retry.max-attempts=3"), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                assertThrows(ModuleUnavailableException.class, orders::read);
                assertThrows(org.springframework.web.client.RestClientResponseException.class, orders::replace);
                assertThrows(org.springframework.web.client.RestClientResponseException.class, orders::reject);
                assertEquals(List.of(1, 1, 1), List.of(stub.count("/read"), stub.count("/replace"),
                        stub.count("/reject")));
            });
        }
    }

    @Test
    void ac_ext_64_zero_load_balancer_instances_is_not_retried() throws Exception {
        String[] properties = props("http://unused", R + "retry.max-attempts=3", R + "retry.initial-backoff=2s",
                R + "retry.max-backoff=2s", "topolith.modules.orders.remote.service-id=nobody");
        List<String> list = new ArrayList<>(List.of(properties));
        list.removeIf(p -> p.startsWith(R + "url="));
        external(list.toArray(String[]::new), context -> {
            long start = System.nanoTime();
            ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                    context.getBean(OrderContract.class)::read);
            assertEquals("no instance", kind(e));
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1500, "no backoff before giving up");
        });
    }

    @Test
    void ac_ext_72_bare_statuses_are_neither_retried_nor_recorded_by_the_breaker() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(401), Reply.status(403), Reply.status(404), Reply.status(405),
                    Reply.status(415));
            external(props(stub.url(), R + "retry.max-attempts=3", R + "circuit-breaker.enabled=true"), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                for (int status : new int[] {401, 403, 404, 405, 415}) {
                    ModuleUnavailableException failure = assertThrows(ModuleUnavailableException.class, orders::read);
                    assertEquals("status " + status, kind(failure));
                }
                assertEquals(5, stub.count("/read"));
                assertEquals(0, breaker(context).getMetrics().getNumberOfBufferedCalls());
            });
        }
    }

    @Test
    void ac_ext_65_provider_hop_failures_open_the_circuit_and_an_open_circuit_rejects_without_calling()
            throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(500));
            external(props(stub.url(), R + "circuit-breaker.enabled=true", R + "circuit-breaker.sliding-window-size=4",
                    R + "circuit-breaker.minimum-number-of-calls=4", R + "circuit-breaker.failure-rate-threshold=50",
                    R + "circuit-breaker.wait-duration-in-open-state=45s"), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                for (int i = 0; i < 4; i++) {
                    assertThrows(org.springframework.web.client.RestClientResponseException.class, orders::read);
                }
                assertEquals(CircuitBreaker.State.OPEN, breaker(context).getState());
                ModuleUnavailableException open = assertThrows(ModuleUnavailableException.class, orders::read);
                assertEquals("circuit open", kind(open));
                assertEquals(4, stub.count("/read"));
                var config = breaker(context).getCircuitBreakerConfig();
                assertEquals(50f, config.getFailureRateThreshold());
                assertEquals(4, config.getSlidingWindowSize());
                assertEquals(4, config.getMinimumNumberOfCalls());
                assertEquals(45, config.getWaitIntervalFunctionInOpenState().apply(1) / 1000);
            });
        }
    }

    @Test
    void ac_ext_65_what_is_not_a_provider_hop_failure_leaves_the_circuit_closed() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/reject", Reply.problem(500, OrderRejectedException.class.getName(), "orders"))
                    .on("/missing", Reply.status(404))
                    .on("/replace", Reply.status(401))
                    .on("/peek", Reply.problem(500, ModuleUnavailableException.class.getName(), "payment"));
            external(props(stub.url(), R + "circuit-breaker.enabled=true", R + "circuit-breaker.sliding-window-size=4",
                    R + "circuit-breaker.minimum-number-of-calls=4"), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                for (int i = 0; i < 6; i++) {
                    assertThrows(OrderRejectedException.class, orders::reject);
                    assertThrows(ModuleUnavailableException.class, orders::missing);
                    assertThrows(ModuleUnavailableException.class, orders::replace);
                    ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class, orders::peek);
                    assertEquals("payment", e.moduleId());
                    assertEquals("unavailable downstream", kind(e));
                }
                assertEquals(CircuitBreaker.State.CLOSED, breaker(context).getState());
                assertEquals(0, breaker(context).getMetrics().getNumberOfBufferedCalls());
                assertEquals("ok", orders.read());
            });
        }
    }

    @Test
    void ac_ext_65_retry_goes_through_the_breaker_and_stops_at_once_when_the_circuit_opens() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(503), Reply.ok()).on("/replace", Reply.status(503));
            external(props(stub.url(), R + "retry.max-attempts=3", R + "retry.initial-backoff=50ms",
                    R + "retry.max-backoff=50ms", R + "circuit-breaker.enabled=true",
                    R + "circuit-breaker.sliding-window-size=2",
                    R + "circuit-breaker.minimum-number-of-calls=2", R + "circuit-breaker.wait-duration-in-open-state"
                            + "=45s"), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                assertEquals("ok", orders.read());
                assertEquals(1, breaker(context).getMetrics().getNumberOfFailedCalls());
                assertEquals(1, breaker(context).getMetrics().getNumberOfSuccessfulCalls());
                breaker(context).reset();
                long start = System.nanoTime();
                ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class, orders::replace);
                assertEquals("circuit open", kind(e));
                assertEquals(2, stub.count("/replace"));
                assertEquals(CircuitBreaker.State.OPEN, breaker(context).getState());
                // one wait of 50ms between attempts 1 and 2; the rejection of attempt 3 does not sleep on
                assertTrue((System.nanoTime() - start) / 1_000_000 < 400);
            });
        }
    }

    @Test
    void ac_ext_66_one_call_records_one_observation_across_its_attempts_on_the_calling_thread() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(503), Reply.ok()).on("/replace", Reply.status(503));
            Observed.STOPPED.clear();
            Observed.THREADS.clear();
            external(props(stub.url(), R + "retry.max-attempts=2", R + "circuit-breaker.enabled=true",
                    "topolith.context.mdc-keys=requestId"), context -> {
                OrderContract orders = context.getBean(OrderContract.class);
                MDC.put("requestId", "r-1");
                try {
                    assertEquals("ok", orders.read());
                    assertThrows(ModuleUnavailableException.class, orders::replace);
                } finally {
                    MDC.remove("requestId");
                }
                List<ModuleCallContext> calls = Observed.STOPPED.stream().filter(c -> c instanceof ModuleCallContext)
                        .map(c -> (ModuleCallContext) c).toList();
                assertEquals(2, calls.size(), Observed.THREADS.toString());
                assertEquals(List.of("none", "ModuleUnavailableException"),
                        calls.stream().map(ModuleCallContext::exception).toList());
                List<Observation.Context> http = Observed.STOPPED.stream()
                        .filter(c -> "http.client.requests".equals(c.getName())).toList();
                assertEquals(4, http.size());
                assertTrue(http.stream().allMatch(c -> c.getParentObservation() != null
                        && c.getParentObservation().getContextView() instanceof ModuleCallContext));
                assertTrue(Observed.THREADS.stream().filter(t -> t.startsWith("http.client.requests@"))
                        .allMatch(t -> t.endsWith("@" + Thread.currentThread().getName())),
                        Observed.THREADS.toString());
                for (String path : List.of("/read", "/replace")) {
                    for (Map<String, List<String>> sent : stub.headers.get("/orders" + path)) {
                        assertEquals(List.of("r-1"), sent.entrySet().stream()
                                .filter(h -> h.getKey().equalsIgnoreCase("Topolith-Mdc-requestId"))
                                .map(Map.Entry::getValue).findFirst().orElse(List.of()), String.valueOf(sent));
                    }
                }
            }, Observed.class);
        }
    }

    @Test
    void ac_ext_66_a_none_module_registers_no_retry_or_breaker_and_an_internal_module_never_retries()
            throws Exception {
        try (Stub stub = new Stub()) {
            external(props(stub.url(), R + "retry.max-attempts=3", R + "circuit-breaker.enabled=true"), context -> {
                assertTrue(context.containsBean("topolithResilience"));
                assertTrue(context.getBean(CircuitBreakerRegistry.class).find("payment").isEmpty());
                assertEquals(java.util.Set.of("orders"), context.getBean(ResilienceModules.class).ids());
            });
            for (String mode : List.of("none", "internal")) {
                external(new String[] {"topolith.modules.orders.mode=" + mode, "topolith.modules.payment.mode=none"},
                        context -> {
                            assertFalse(context.containsBean("topolithResilience"));
                            assertTrue(context.getBeansOfType(ResilienceModules.class).isEmpty());
                            assertTrue(context.getBean(CircuitBreakerRegistry.class).getAllCircuitBreakers()
                                    .isEmpty());
                        });
            }
        }
    }

    @Test
    void ac_ext_66_a_loopback_module_retries_an_idempotent_call_and_draws_a_fault_per_attempt() throws Exception {
        String[] fault = {"topolith.modules.orders.mode=loopback", "topolith.modules.payment.mode=none",
                "topolith.modules.orders.loopback.fault.rate=1", "topolith.modules.orders.loopback.fault.kind=timeout",
                "topolith.modules.orders.loopback.latency=20ms", R + "read-timeout=1s", "server.port=0",
                R + "retry.max-attempts=3", R + "retry.initial-backoff=1ms", R + "retry.max-backoff=5ms"};
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()},
                ResilienceTest.class.getClassLoader())) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(OrderHost.class).withPropertyValues(fault)
                    .run((AssertableWebApplicationContext context) -> {
                        assertNull(context.getStartupFailure());
                        OrderContract orders = context.getBean(OrderCaller.class).orders;
                        OrderController provider = org.springframework.test.util.AopTestUtils
                                .getUltimateTargetObject(context.getBean(OrderController.class));
                        assertEquals("timeout", kind(assertThrows(ModuleUnavailableException.class, orders::read)));
                        // the provider ran on each timed-out attempt (D-103)
                        assertEquals(3, provider.calls.get());
                        provider.calls.set(0);
                        assertThrows(ModuleUnavailableException.class, orders::save);
                        assertEquals(1, provider.calls.get());
                    });
        }
    }

    @Test
    void ac_ext_67_tuning_and_threshold_keys_start_with_retry_and_the_breaker_off() throws Exception {
        try (Stub stub = new Stub()) {
            external(props(stub.url(), R + "retry.multiplier=3", R + "retry.safe-methods-idempotent=true",
                    R + "circuit-breaker.sliding-window-size=20", R + "circuit-breaker.enabled=false"), context -> {
                assertTrue(context.getBean(CircuitBreakerRegistry.class).getAllCircuitBreakers().isEmpty());
                assertEquals("ok", context.getBean(OrderContract.class).read());
            });
        }
    }

    @Test
    void ac_ext_67_the_global_http_service_breaker_defaults_off_only_while_retry_or_the_breaker_is_on()
            throws Exception {
        String key = "spring.cloud.circuitbreaker.http-services.enabled";
        external(props("http://127.0.0.1:1", R + "retry.max-attempts=2"),
                context -> assertEquals("false", context.getEnvironment().getProperty(key)));
        external(props("http://127.0.0.1:1", R + "circuit-breaker.enabled=true"),
                context -> assertEquals("false", context.getEnvironment().getProperty(key)));
        external(props("http://127.0.0.1:1", R + "retry.max-attempts=1"),
                context -> assertNull(context.getEnvironment().getProperty(key)));
    }

    @Test
    void ac_ext_67_the_global_http_service_breaker_with_retry_or_the_breaker_fails_with_tl0003() throws Exception {
        String global = "spring.cloud.circuitbreaker.http-services.enabled=true";
        startupFails(props("http://127.0.0.1:1", R + "retry.max-attempts=2", global), TopolithCode.TL0003,
                "max-attempts");
        startupFails(props("http://127.0.0.1:1", R + "circuit-breaker.enabled=true", global), TopolithCode.TL0003,
                "circuit-breaker.enabled");
    }

    @Test
    void ac_ext_67_another_source_configuring_breaker_orders_fails_with_tl0003() throws Exception {
        String on = R + "circuit-breaker.enabled=true";
        startupFails(props("http://127.0.0.1:1", on), TopolithCode.TL0003, "'orders'", ConfiguresOrders.class);
        startupFails(props("http://127.0.0.1:1", on), TopolithCode.TL0003, "already in the CircuitBreakerRegistry",
                RegistersOrders.class);
        for (String key : List.of("resilience4j.circuitbreaker.instances.orders.sliding-window-size=3",
                "resilience4j.circuitbreaker.configs.orders.sliding-window-size=3",
                "resilience4j.timelimiter.instances.orders.timeout-duration=1s",
                "spring.cloud.circuitbreaker.resilience4j.disable-time-limiter-map.orders=true")) {
            startupFails(props("http://127.0.0.1:1", on, key), TopolithCode.TL0003,
                    key.substring(0, key.indexOf(".orders.") + 7));
        }
    }

    @Test
    void ac_ext_67_a_host_default_configuration_does_not_reach_the_breaker_of_orders() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.slow(100));
            external(props(stub.url(), R + "circuit-breaker.enabled=true", R + "read-timeout=2s"), context -> {
                assertEquals("late", context.getBean(OrderContract.class).read());
                assertEquals(100, breaker(context).getCircuitBreakerConfig().getSlidingWindowSize());
            }, ConfiguresDefault.class);
        }
    }

    @Test
    void ac_ext_67_a_host_fallback_for_a_module_group_fails_with_tl0003() throws Exception {
        startupFails(props("http://127.0.0.1:1"), TopolithCode.TL0003, "@HttpServiceFallback", FallsBack.class);
    }

    @Test
    void ac_ext_67_a_default_host_fallback_for_every_group_fails_with_tl0003() throws Exception {
        startupFails(props("http://127.0.0.1:1"), TopolithCode.TL0003, "@HttpServiceFallback",
                FallsBackEverywhere.class);
    }

    @Test
    void ac_ext_63_zero_backoff_retries_without_waiting() throws Exception {
        try (Stub stub = new Stub()) {
            stub.on("/read", Reply.status(503), Reply.status(503), Reply.ok());
            external(props(stub.url(), R + "retry.max-attempts=3", R + "retry.initial-backoff=0",
                    R + "retry.max-backoff=0"), context -> {
                assertEquals("ok", context.getBean(OrderContract.class).read());
                assertEquals(3, stub.count("/read"));
            });
        }
    }

    @Test
    void ac_ext_67_zero_or_two_factories_fail_with_tl0041_naming_the_module() throws Exception {
        String on = R + "circuit-breaker.enabled=true";
        startupFails(props("http://127.0.0.1:1", on, "spring.cloud.circuitbreaker.resilience4j.enabled=false"),
                TopolithCode.TL0041, "module 'orders'");
        startupFails(props("http://127.0.0.1:1", on), TopolithCode.TL0041, "module 'orders'", SecondFactory.class);
        startupFails(props("http://127.0.0.1:1", on, "spring.cloud.circuitbreaker.resilience4j.enabled=false"),
                TopolithCode.TL0041, "OtherFactory", SecondFactory.class);
    }

    private static final String EXCLUDED = "spring.autoconfigure.exclude="
            + "io.github.rey5137.topolith.spring.cloud.TopolithResilienceAutoConfiguration";

    @Test
    void ac_ext_67_retry_or_a_breaker_with_the_resilience_configuration_excluded_fails_with_tl0041()
            throws Exception {
        startupFails(props("http://127.0.0.1:1", R + "retry.max-attempts=2", EXCLUDED), TopolithCode.TL0041,
                "topolith.modules.orders.remote.retry.max-attempts");
        startupFails(props("http://127.0.0.1:1", R + "circuit-breaker.enabled=true", EXCLUDED), TopolithCode.TL0041,
                "topolith.modules.orders.remote.circuit-breaker.enabled");
        // both off: the module starts with no resilience and no diagnostic
        external(props("http://127.0.0.1:1", EXCLUDED), context -> assertNotNull(context.getBean(OrderContract.class)));
    }

    @Test
    void ac_ext_67_a_loopback_module_with_retry_and_the_resilience_configuration_excluded_fails_with_tl0041()
            throws Exception {
        String[] properties = {"topolith.modules.orders.mode=loopback", "topolith.modules.payment.mode=none",
                R + "retry.max-attempts=2", "server.port=0", EXCLUDED};
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()},
                ResilienceTest.class.getClassLoader())) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(OrderHost.class).withPropertyValues(properties)
                    .run((AssertableWebApplicationContext context) -> {
                        Throwable cause = context.getStartupFailure();
                        while (cause != null && !(cause instanceof TopolithException)) {
                            cause = cause.getCause();
                        }
                        TopolithException failure = assertInstanceOf(TopolithException.class, cause,
                                String.valueOf(context.getStartupFailure()));
                        assertTrue(failure.getMessage().contains("TL0041"), failure.getMessage());
                    });
        }
    }

    @Test
    void ac_desc_13_a_method_only_the_module_descriptor_declares_idempotent_is_not_retried() throws Exception {
        String api = PACKAGE + ".orders.api";
        write("modules", "orders", out -> Descriptors.write(ModuleDescriptor.builder("orders")
                .packageName(PACKAGE + ".orders").apiPackages(List.of(api)).apiVersion("1.0.0")
                .provides(List.of(CONTRACT)).idempotent(List.of(CONTRACT + "#missing", CONTRACT + "#read",
                        CONTRACT + "#reject", CONTRACT + "#replace", CONTRACT + "#peek")).build(), out));
        try (Stub stub = new Stub()) {
            stub.on("/peek", Reply.status(503));
            external(props(stub.url(), R + "retry.max-attempts=3"), context -> {
                assertThrows(ModuleUnavailableException.class, context.getBean(OrderContract.class)::peek);
                assertEquals(1, stub.count("/peek"));
            });
        }
    }

    /** A host that declares Spring Cloud's global breaker configurer itself stacks it on the module's group. */
    @Configuration(proxyBeanMethods = false)
    static class DeclaresGlobalBreaker {
        @Bean
        org.springframework.cloud.client.circuitbreaker.httpservice.CircuitBreakerRestClientHttpServiceGroupConfigurer
                global(CircuitBreakerFactory<?, ?> factory) {
            return new org.springframework.cloud.client.circuitbreaker.httpservice
                    .CircuitBreakerRestClientHttpServiceGroupConfigurer(factory);
        }
    }

    @Test
    void ac_ext_67_a_global_breaker_configurer_in_the_context_fails_a_resilient_module_with_tl0003()
            throws Exception {
        startupFails(props("http://127.0.0.1:1", R + "retry.max-attempts=2",
                "spring.cloud.circuitbreaker.http-services.enabled=false"), TopolithCode.TL0003,
                "global HTTP-service circuit breaker", DeclaresGlobalBreaker.class);
    }
}
