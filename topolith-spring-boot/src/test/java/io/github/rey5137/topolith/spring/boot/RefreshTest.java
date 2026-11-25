package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.separate.PlainHttp;
import io.github.rey5137.topolith.spring.boot.errorfixture.separate.api.PlainContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.separate.api.PlainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2.6: a refresh rebuilds an external module's client without replacing its proxy (R-EXT-08, D-14, D-50). */
@ExtendWith(OutputCaptureExtension.class)
class RefreshTest {
    private static final String URL = "topolith.modules.separate.remote.url";
    private static final String HTTP = "topolith.modules.separate.remote.http-interface."
            + PlainContract.class.getName();

    @TempDir
    Path root;

    @BeforeEach
    void descriptors() throws Exception {
        ContractExceptionsTest.contracts(root, "separate", PlainContract.class);
        ContractExceptionsTest.contracts(root, "payment");
    }

    /** A provider that answers every request with {@code body} after the optional gate. */
    private static HttpServer stub(String body, long delayMillis, Consumer<String> seen) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.accept(exchange.getRequestURI().getPath());
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void caller(Consumer<AssertableApplicationContext> check, Class<?> extra, String... properties)
            throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class, extra)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.separate.mode=external",
                            "topolith.modules.payment.mode=none", "management.endpoints.web.exposure.include=topolith",
                            HTTP + "=" + PlainHttp.class.getName())
                    .withPropertyValues(properties).run(context -> {
                        assertNull(context.getStartupFailure());
                        check.accept(context);
                    });
        }
    }

    /** Changes properties as a refresh would, then hands the keys to Topolith. */
    private static void refresh(AssertableApplicationContext context, String... pairs) {
        var environment = context.getSourceApplicationContext(ConfigurableApplicationContext.class).getEnvironment();
        if (environment.getPropertySources().get("refreshed") == null) {
            environment.getPropertySources().addFirst(new MapPropertySource("refreshed", new HashMap<>()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) environment.getPropertySources().get("refreshed")
                .getSource();
        Set<String> keys = new java.util.HashSet<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null) {
                values.remove(pairs[i]);
            } else {
                values.put(pairs[i], pairs[i + 1]);
            }
            keys.add(pairs[i]);
        }
        context.getBean(TopolithRefresh.class).apply(keys);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> module(AssertableApplicationContext context, String id) {
        return (Map<String, Object>) ((Map<String, Object>) context.getBean(TopolithEndpoint.class).modules()
                .get("modules")).get(id);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class Customization {
        static final AtomicReference<String> HEADER = new AtomicReference<>();
        static final List<Integer> PORTS = new java.util.concurrent.CopyOnWriteArrayList<>();

        @org.springframework.context.annotation.Bean
        RestClientHttpServiceGroupConfigurer custom() {
            return ModuleClients.restClientBuilder("separate", builder -> builder.defaultHeader("X-Custom", "yes")
                    .requestInterceptor((request, body, execution) -> {
                        PORTS.add(request.getURI().getPort());
                        HEADER.set(request.getHeaders().getFirst("X-Custom") + request.getURI().getPath());
                        return execution.execute(request, body);
                    }));
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class NoExtra {
    }

    @Test
    void ac_ext_18_url_change_rebuilds_and_keeps_customizer_translation_and_mapping() throws Exception {
        Customization.PORTS.clear();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            WebApplicationContextRunner provider = new WebApplicationContextRunner(
                    AnnotationConfigServletWebServerApplicationContext::new).withClassLoader(loader)
                    .withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                            "topolith.modules.separate.mode=internal", "topolith.modules.payment.mode=none",
                            "topolith.modules.separate.expose.enabled=true");
            provider.run(first -> provider.run(second -> {
                String one = "http://127.0.0.1:" + ProviderErrorsTest.port(first);
                String two = "http://127.0.0.1:" + ProviderErrorsTest.port(second);
                try {
                    caller(context -> {
                        PlainContract client = context.getBean(PlainContract.class);
                        assertEquals("found ok", client.find("ok"));
                        assertEquals(ProviderErrorsTest.port(first), Customization.PORTS.get(0));
                        refresh(context, URL, two);
                        assertSame(client, context.getBean(PlainContract.class));
                        assertEquals("found ok", client.find("ok"));
                        assertEquals("failed", assertThrows(PlainException.class, () -> client.find("fail"))
                                .getMessage());
                        assertEquals(ProviderErrorsTest.port(second), Customization.PORTS.get(1));
                        assertEquals(ProviderErrorsTest.port(second), Customization.PORTS.get(2));
                        assertEquals("yes/separate/find", Customization.HEADER.get());
                        assertEquals(two + "/separate", context.getEnvironment()
                                .getProperty("spring.http.serviceclient.separate.base-url"));
                        assertFalse(module(context, "separate").containsKey("refresh"));
                    }, Customization.class, URL + "=" + one);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
    }

    @Test
    void ac_ext_19_timeout_change_rebuilds() throws Exception {
        HttpServer slow = stub("found slow", 700, path -> { });
        try {
            caller(context -> {
                PlainContract client = context.getBean(PlainContract.class);
                assertEquals("found slow", client.find("x"));
                refresh(context, "topolith.modules.separate.remote.read-timeout", "150ms");
                assertThrows(RuntimeException.class, () -> client.find("x"));
                refresh(context, "topolith.modules.separate.remote.read-timeout", "3s",
                        "topolith.modules.separate.remote.connect-timeout", "1s");
                assertEquals("found slow", client.find("x"));
            }, NoExtra.class, URL + "=" + url(slow), "topolith.modules.separate.remote.read-timeout=3s");
        } finally {
            slow.stop(0);
        }
    }

    @Test
    void ac_ext_21_a_call_in_flight_finishes_on_the_old_client() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer oldServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        oldServer.createContext("/", exchange -> {
            arrived.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "found old".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        oldServer.start();
        HttpServer newServer = stub("found new", 0, path -> { });
        try {
            caller(context -> {
                PlainContract client = context.getBean(PlainContract.class);
                CompletableFuture<String> inFlight = CompletableFuture.supplyAsync(() -> client.find("x"));
                try {
                    assertTrue(arrived.await(10, TimeUnit.SECONDS));
                    refresh(context, URL, url(newServer));
                    assertEquals("found new", client.find("x"));
                    release.countDown();
                    assertEquals("found old", inFlight.get(10, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    release.countDown();
                }
            }, NoExtra.class, URL + "=" + url(oldServer), "topolith.modules.separate.remote.read-timeout=15s");
        } finally {
            oldServer.stop(0);
            newServer.stop(0);
        }
    }

    @Test
    void ac_ext_23_invalid_settings_keep_the_old_client(CapturedOutput output) throws Exception {
        HttpServer server = stub("found old", 0, path -> { });
        try {
            caller(context -> {
                PlainContract client = context.getBean(PlainContract.class);
                String base = context.getEnvironment().getProperty("spring.http.serviceclient.separate.base-url");
                String[][] invalid = {{URL, "not a url"}, {URL, "ftp://127.0.0.1:1"},
                        {"topolith.modules.separate.remote.service-id", "other"},
                        {"topolith.modules.separate.remote.connect-timeout", "soon"}};
                for (String[] change : invalid) {
                    refresh(context, change);
                    Map<String, Object> failure = (Map<String, Object>) module(context, "separate").get("refresh");
                    assertNotNull(failure, change[0] + "=" + change[1]);
                    assertEquals("failed", failure.get("status"));
                    assertEquals("TL0011", failure.get("code"));
                    assertNotNull(failure.get("message"));
                    assertNotNull(failure.get("at"));
                    assertEquals("found old", client.find("x"));
                    assertEquals(base, context.getEnvironment()
                            .getProperty("spring.http.serviceclient.separate.base-url"));
                    refresh(context, change[0], change[0].endsWith("url") ? url(server) : "");
                }
                assertTrue(output.getAll().contains("TL0011"), output.getAll());
                // a later valid refresh clears the failure
                refresh(context, URL, url(server) + "/");
                assertFalse(module(context, "separate").containsKey("refresh"));
            }, NoExtra.class, URL + "=" + url(server));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_ext_24_a_failed_rebuild_keeps_the_old_client(CapturedOutput output) throws Exception {
        HttpServer oldServer = stub("found old", 0, path -> { });
        HttpServer newServer = stub("found new", 0, path -> { });
        AtomicBoolean fail = new AtomicBoolean();
        try {
            ClientHttpRequestFactoryBuilder<?> jdk = ClientHttpRequestFactoryBuilder.jdk();
            ClientHttpRequestFactoryBuilder<ClientHttpRequestFactory> failing = settings -> {
                if (fail.get()) {
                    throw new IllegalStateException("no client for you");
                }
                return jdk.build(settings);
            };
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                        .withBean("failingFactories", ClientHttpRequestFactoryBuilder.class, () -> failing)
                        .withPropertyValues("spring.liquibase.enabled=false",
                                "topolith.modules.separate.mode=external", "topolith.modules.payment.mode=none",
                                "management.endpoints.web.exposure.include=topolith", URL + "=" + url(oldServer),
                                HTTP + "=" + PlainHttp.class.getName())
                        .run(context -> {
                            assertNull(context.getStartupFailure());
                            PlainContract client = context.getBean(PlainContract.class);
                            fail.set(true);
                            refresh(context, URL, url(newServer));
                            assertEquals("found old", client.find("x"));
                            Map<String, Object> failure = (Map<String, Object>) module(context, "separate")
                                    .get("refresh");
                            assertEquals("no client for you", failure.get("message"));
                            assertEquals("TL0011", failure.get("code"));
                            assertTrue(output.getAll().contains("TL0011"), output.getAll());
                            fail.set(false);
                            refresh(context, URL, url(newServer));
                            assertEquals("found new", client.find("x"));
                            assertFalse(module(context, "separate").containsKey("refresh"));
                        });
            }
        } finally {
            oldServer.stop(0);
            newServer.stop(0);
        }
    }

    @Test
    void a_rebuilt_client_rechecks_its_remote_version() throws Exception {
        AtomicInteger oldHits = new AtomicInteger();
        AtomicInteger newHits = new AtomicInteger();
        HttpServer oldServer = stub("found old", 0, path -> oldHits.incrementAndGet());
        HttpServer newServer = stub("3.0.0", 0, path -> {
            if (path.equals("/separate/.topolith/api-version")) {
                newHits.incrementAndGet();
            }
        });
        try {
            caller(context -> {
                assertEquals(0, oldHits.get());
                refresh(context, URL, url(newServer));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (newHits.get() == 0 && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertEquals(1, newHits.get());
                assertEquals(0, oldHits.get());
            }, NoExtra.class, URL + "=" + url(oldServer));
        } finally {
            oldServer.stop(0);
            newServer.stop(0);
        }
    }

    @Test
    void inv_2_refresh_leaves_a_none_module_and_unchanged_modules_alone(CapturedOutput output) throws Exception {
        HttpServer server = stub("found old", 0, path -> { });
        try {
            caller(context -> {
                ExternalClientEndpoints endpoints = context.getBean(ExternalClientEndpoints.class);
                assertFalse(endpoints.manages("payment"));
                refresh(context, "topolith.modules.payment.remote.url", "http://127.0.0.1:1",
                        "topolith.modules.payment.remote.connect-timeout", "1s", "spring.other.key", "x");
                assertFalse(output.getAll().contains("TL0011"), output.getAll());
                assertFalse(output.getAll().contains("TL0010"), output.getAll());
                assertFalse(module(context, "payment").containsKey("refresh"));
                assertFalse((boolean) context.getBean(TopolithEndpoint.class).modules().get("restartPending"));
                assertNull(context.getEnvironment().getProperty("spring.http.serviceclient.payment.base-url"));
                assertEquals("found old", context.getBean(PlainContract.class).find("x"));
            }, NoExtra.class, URL + "=" + url(server));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_ext_19_a_removed_timeout_falls_back_to_the_default_as_at_startup() throws Exception {
        HttpServer slow = stub("found slow", 700, path -> { });
        try {
            caller(context -> {
                PlainContract client = context.getBean(PlainContract.class);
                refresh(context, "topolith.modules.separate.remote.read-timeout", "150ms");
                assertThrows(RuntimeException.class, () -> client.find("x"));
                refresh(context, "topolith.modules.separate.remote.read-timeout", null);
                assertEquals("found slow", client.find("x"));
            }, NoExtra.class, URL + "=" + url(slow));
        } finally {
            slow.stop(0);
        }
    }

    @Test
    void ac_ext_19_a_removed_timeout_falls_back_to_boots_global_value() throws Exception {
        HttpServer slow = stub("found slow", 700, path -> { });
        try {
            caller(context -> {
                PlainContract client = context.getBean(PlainContract.class);
                refresh(context, "topolith.modules.separate.remote.read-timeout", "150ms");
                assertThrows(RuntimeException.class, () -> client.find("x"));
                refresh(context, "topolith.modules.separate.remote.read-timeout", null);
                assertEquals("found slow", client.find("x"));
                // Boot's global read timeout stands, not the default of 5s
                refresh(context, "spring.http.clients.read-timeout", "150ms",
                        "topolith.modules.separate.remote.connect-timeout", "1s");
                assertThrows(RuntimeException.class, () -> client.find("x"));
            }, NoExtra.class, URL + "=" + url(slow), "spring.http.clients.read-timeout=3s");
        } finally {
            slow.stop(0);
        }
    }

    @Test
    void ac_mode_13_an_unresolvable_requested_mode_is_a_pending_restart_not_a_failure(CapturedOutput output)
            throws Exception {
        HttpServer server = stub("found old", 0, path -> { });
        try {
            caller(context -> {
                refresh(context, "topolith.modules.separate.mode", "bogus");
                String log = output.getAll();
                assertTrue(log.contains("TL0010"), log);
                assertTrue(log.contains("module 'separate' runs in mode external and the refreshed configuration "
                        + "requests 'bogus'"), log);
                assertTrue(log.contains("(TL0003)"), log);
                assertFalse(log.contains("TL0011"), log);
                assertEquals(true, context.getBean(TopolithEndpoint.class).modules().get("restartPending"));
                assertEquals("bogus", module(context, "separate").get("requestedMode"));
                assertEquals("found old", context.getBean(PlainContract.class).find("x"));
                refresh(context, "topolith.modules.separate.mode", "external");
                assertEquals(false, context.getBean(TopolithEndpoint.class).modules().get("restartPending"));
            }, NoExtra.class, URL + "=" + url(server));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void endpoint_reports_no_restart_pending_by_default() throws Exception {
        caller(context -> {
            Map<String, Object> report = context.getBean(TopolithEndpoint.class).modules();
            assertEquals(false, report.get("restartPending"));
            assertEquals(Map.of("mode", "external"), module(context, "separate"));
        }, NoExtra.class, URL + "=http://127.0.0.1:1");
    }
}
