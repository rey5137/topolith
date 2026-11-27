package io.github.rey5137.topolith.spring.test;

import java.net.InetSocketAddress;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class TopolithTestSupportTest {
    @Test
    void ac_test_25_no_checks_and_negative_timeout() {
        new ApplicationContextRunner().run(context -> {
            assertTrue(TopolithTestSupport.awaitVersionChecks(context, Duration.ZERO));
            assertThrows(IllegalArgumentException.class,
                    () -> TopolithTestSupport.awaitVersionChecks(context, Duration.ofNanos(-1)));
        });
    }

    @Test
    void ac_test_25_none_module_finishes_immediately() throws Exception {
        try (URLClassLoader fixtures = FixtureLoader.create()) {
            new ApplicationContextRunner().withClassLoader(fixtures)
                    .withUserConfiguration(TopolithTestModesTest.Host.class)
                    .withPropertyValues("topolith.modules.payment.mode=none").run(context -> {
                        assertNull(context.getStartupFailure());
                        ready(context.getSourceApplicationContext());
                        assertTrue(TopolithTestSupport.awaitVersionChecks(context, Duration.ZERO));
                    });
        }
    }

    @Test
    void ac_test_25_waits_for_failure_and_mismatch_logs(CapturedOutput output) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader fixtures = FixtureLoader.create()) {
            Thread.currentThread().setContextClassLoader(fixtures);
            runner(fixtures, "http://127.0.0.1:1").run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.getBeansOfType(io.github.rey5137.topolith.spring.boot.internal.VersionChecks.class)
                        .isEmpty(), "version checks bean missing");
                assertTrue(TopolithTestSupport.awaitVersionChecks(context, Duration.ZERO));
                ready(context.getSourceApplicationContext());
                assertTrue(TopolithTestSupport.awaitVersionChecks(context, Duration.ofSeconds(10)));
                assertTrue(output.getAll().contains("TL0015"), output.getAll());
            });
            CountDownLatch arrived = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                arrived.countDown();
                try {
                    if (release.await(5, TimeUnit.SECONDS)) {
                        byte[] body = "9.0.0".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                runner(fixtures, "http://127.0.0.1:" + server.getAddress().getPort()).run(context -> {
                    ready(context.getSourceApplicationContext());
                    assertTrue(arrived.await(5, TimeUnit.SECONDS));
                    assertFalse(TopolithTestSupport.awaitVersionChecks(context, Duration.ofMillis(20)));
                    release.countDown();
                    assertTrue(TopolithTestSupport.awaitVersionChecks(context, Duration.ofSeconds(10)));
                    assertTrue(output.getAll().contains("TL0023"), output.getAll());
                });
            } finally {
                release.countDown();
                server.stop(0);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static ApplicationContextRunner runner(ClassLoader loader, String url) {
        return new ApplicationContextRunner().withClassLoader(loader)
                .withUserConfiguration(TopolithTestModesTest.Host.class)
                .withPropertyValues("topolith.modules.payment.mode=external", "topolith.modules.payment.remote.url="
                        + url, "topolith.modules.payment.remote.read-timeout=5s");
    }

    private static void ready(org.springframework.context.ConfigurableApplicationContext context) {
        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
    }
}
