package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ClientHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2.4: the readiness check of an external module's remote API version (R-VAL-04, D-24). */
@ExtendWith(OutputCaptureExtension.class)
class VersionReadinessTest {
    private static final String PATH = "/account/.topolith/api-version";

    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        ContractExceptionsTest.contracts(root, "account", AccountContract.class);
        ContractExceptionsTest.contracts(root, "payment");
    }

    private record Stub(HttpServer server, AtomicInteger hits, AtomicReference<String> path) {
        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
    }

    /** A provider answering the version endpoint with {@code status} and {@code body} after {@code delay}. */
    private static Stub stub(int status, String body, long delayMillis) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> path = new AtomicReference<>();
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            try {
                Thread.sleep(delayMillis);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (IOException | InterruptedException e) {
                // the caller gave up
            } finally {
                exchange.close();
            }
        });
        server.start();
        return new Stub(server, hits, path);
    }

    /** Starts a caller host, publishes the ready event twice and hands the context to {@code check}. */
    private void ready(String url, Consumer<AssertableApplicationContext> check) throws Exception {
        ready(url, false, check);
    }

    private void ready(String url, boolean lazy, Consumer<AssertableApplicationContext> check) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            ApplicationContextRunner runner = new ApplicationContextRunner();
            if (lazy) {
                runner = runner.withInitializer(context -> context.addBeanFactoryPostProcessor(
                        new LazyInitializationBeanFactoryPostProcessor()));
            }
            runner.withClassLoader(loader).withUserConfiguration(ClientHost.class)
                    .withBean(ApplicationAvailability.class, ApplicationAvailabilityBean::new)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.payment.mode=none",
                            "topolith.modules.account.mode=external", "topolith.modules.account.remote.url=" + url,
                            "topolith.modules.account.remote.connect-timeout=1s",
                            "topolith.modules.account.remote.read-timeout=1s")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var source = context.getSourceApplicationContext();
                        AvailabilityChangeEvent.publish(source, ReadinessState.ACCEPTING_TRAFFIC);
                        ApplicationReadyEvent event = new ApplicationReadyEvent(new SpringApplication(), new String[0],
                                source, Duration.ZERO);
                        source.publishEvent(event);
                        source.publishEvent(event);
                        assertTrue(context.getBean(VersionReadiness.class).await(Duration.ofSeconds(10)));
                        // the check never changes readiness
                        if (!lazy) {
                            // (a lazy availability bean would have missed the event above)
                            assertEquals(ReadinessState.ACCEPTING_TRAFFIC,
                                    context.getBean(ApplicationAvailability.class).getReadinessState());
                        }
                        check.accept(context);
                    });
        }
    }

    private static int count(CapturedOutput output, String text) {
        return output.getAll().split(text, -1).length - 1;
    }

    private void warnsOnce(Stub stub, String reason, CapturedOutput output) throws Exception {
        try {
            ready(stub.url(), context -> {
                assertEquals(1, count(output, "TL0015"), output.getAll());
                assertEquals(0, count(output, "TL0023"));
                assertTrue(output.getAll().contains("module 'account'"));
                assertTrue(output.getAll().contains(reason), output.getAll());
                assertEquals(PATH, stub.path().get());
            });
        } finally {
            stub.server().stop(0);
        }
    }

    @Test
    void ac_val_07_a_major_mismatch_warns_once(CapturedOutput output) throws Exception {
        Stub stub = stub(200, "2.1.0\n", 0);
        try {
            ready(stub.url(), context -> {
                assertEquals(1, count(output, "TL0023"), output.getAll());
                assertEquals(0, count(output, "TL0015"));
                assertTrue(output.getAll().contains("module 'account'"));
                assertTrue(output.getAll().contains("1.0.0"));
                assertTrue(output.getAll().contains("2.1.0"));
                assertEquals(PATH, stub.path().get());
                // checked once per context, not per event
                assertEquals(1, stub.hits().get());
            });
        } finally {
            stub.server().stop(0);
        }
    }

    @Test
    void ac_val_07_a_matching_major_is_silent(CapturedOutput output) throws Exception {
        Stub stub = stub(200, "1.9.3-SNAPSHOT", 0);
        try {
            ready(stub.url(), context -> assertEquals(1, stub.hits().get()));
            assertFalse(output.getAll().contains("TL0023"));
            assertFalse(output.getAll().contains("TL0015"));
        } finally {
            stub.server().stop(0);
        }
    }

    @Test
    void ac_val_10_an_unreachable_endpoint_warns(CapturedOutput output) throws Exception {
        ready("http://127.0.0.1:1", context -> {
            assertEquals(1, count(output, "TL0015"), output.getAll());
            assertTrue(output.getAll().contains("could not be reached"), output.getAll());
        });
    }

    @Test
    void ac_val_10_a_slow_endpoint_is_cut_by_the_client_timeouts(CapturedOutput output) throws Exception {
        Stub stub = stub(200, "1.0.0", 3000);
        try {
            long start = System.nanoTime();
            ready(stub.url(), context -> {
                assertEquals(1, count(output, "TL0015"), output.getAll());
                assertTrue(output.getAll().contains("could not be reached"), output.getAll());
            });
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(8)) < 0);
        } finally {
            stub.server().stop(0);
        }
    }

    @Test
    void ac_val_11_response_401_warns(CapturedOutput output) throws Exception {
        warnsOnce(stub(401, "no", 0), "HTTP 401", output);
    }

    @Test
    void ac_val_11_response_403_warns(CapturedOutput output) throws Exception {
        warnsOnce(stub(403, "no", 0), "HTTP 403", output);
    }

    @Test
    void ac_val_12_response_404_warns(CapturedOutput output) throws Exception {
        warnsOnce(stub(404, "", 0), "HTTP 404", output);
    }

    @Test
    void ac_val_13_a_5xx_response_warns(CapturedOutput output) throws Exception {
        warnsOnce(stub(503, "down", 0), "HTTP 503", output);
    }

    @Test
    void ac_val_13_an_unreadable_2xx_body_warns(CapturedOutput output) throws Exception {
        warnsOnce(stub(200, "<html>sign in</html>", 0), "without a readable version", output);
    }

    @Test
    void ac_val_13_an_empty_2xx_body_warns(CapturedOutput output) throws Exception {
        warnsOnce(stub(204, "", 0), "without a readable version", output);
    }

    @Test
    void ac_val_10_the_remote_check_does_not_hold_up_the_ready_event(CapturedOutput output) throws Exception {
        // the provider answers only after the client's 1s read timeout, so a synchronous check could not
        // have logged yet when the event returns, and an asynchronous one cannot have finished either
        Stub stub = stub(200, "1.0.0", 3000);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ClientHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.payment.mode=none",
                            "topolith.modules.account.mode=external", "topolith.modules.account.remote.url="
                                    + stub.url(), "topolith.modules.account.remote.read-timeout=1s")
                    .run(context -> {
                        var source = context.getSourceApplicationContext();
                        long start = System.nanoTime();
                        source.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                                source, Duration.ZERO));
                        boolean returnedFirst = !output.getAll().contains("TL0015");
                        long taken = System.nanoTime() - start;
                        assertTrue(context.getBean(VersionReadiness.class).await(Duration.ofSeconds(10)));
                        assertTrue(returnedFirst, "the event returned only after the check: " + taken);
                        assertEquals(1, count(output, "TL0015"), output.getAll());
                    });
        } finally {
            stub.server().stop(0);
        }
    }

    @Test
    void ac_val_10_lazy_initialization_does_not_skip_the_remote_check(CapturedOutput output) throws Exception {
        Stub stub = stub(200, "5.0.0", 0);
        try {
            ready(stub.url(), true, context -> {
                assertEquals(1, count(output, "TL0023"), output.getAll());
                assertEquals(1, stub.hits().get());
            });
        } finally {
            stub.server().stop(0);
        }
    }

    @Test
    void ac_val_15_close_interrupts_a_check_and_suppresses_its_warning(CapturedOutput output) throws Exception {
        Stub stub = stub(200, "2.0.0", 5000);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ClientHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.payment.mode=none",
                            "topolith.modules.account.mode=external", "topolith.modules.account.remote.url="
                                    + stub.url(), "topolith.modules.account.remote.read-timeout=5s")
                    .run(context -> {
                        var source = context.getSourceApplicationContext();
                        var checks = context.getBean(VersionReadiness.class);
                        source.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                                source, Duration.ZERO));
                        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                        while (stub.hits().get() == 0 && System.nanoTime() < deadline) {
                            Thread.sleep(10);
                        }
                        assertEquals(1, stub.hits().get());
                        assertFalse(checks.await(Duration.ZERO));
                        source.close();
                        assertTrue(checks.await(Duration.ofSeconds(2)), "the close should stop the check");
                        assertFalse(output.getAll().contains("TL0015"), output.getAll());
                        assertFalse(output.getAll().contains("TL0023"), output.getAll());
                    });
        } finally {
            stub.server().stop(0);
        }
    }

    @Test
    void ac_test_25_waits_for_a_refresh_check_too() throws Exception {
        Stub stub = stub(200, "1.0.0", 200);
        try {
            ready(stub.url(), context -> {
                var checks = context.getBean(VersionReadiness.class);
                var resolution = context.getBean(TopologyResolver.RESOLUTION_BEAN, TopologyResolver.Resolution.class);
                checks.startRemote(resolution, Set.of("account"));
                assertFalse(checks.await(Duration.ZERO));
                assertTrue(checks.await(Duration.ofSeconds(10)));
                assertEquals(2, stub.hits().get());
            });
        } finally {
            stub.server().stop(0);
        }
    }
}
