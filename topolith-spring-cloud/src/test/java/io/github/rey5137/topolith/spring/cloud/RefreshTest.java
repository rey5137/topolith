package io.github.rey5137.topolith.spring.cloud;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.spring.cloud.fixture.host.Host;
import io.github.rey5137.topolith.spring.cloud.fixture.user.api.UserContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2.6: an {@code EnvironmentChangeEvent} reaches Topolith through the optional Cloud integration (D-20, D-50). */
@ExtendWith(OutputCaptureExtension.class)
class RefreshTest {
    private static final String PACKAGE = "io.github.rey5137.topolith.spring.cloud.fixture";
    private static final String USER_URL = "topolith.modules.user.remote.url";
    private static final String USER_SERVICE = "topolith.modules.user.remote.service-id";

    @TempDir
    Path root;

    @BeforeEach
    void descriptors() throws IOException {
        descriptor("user", List.of(UserContract.class.getName()));
        descriptor("payment", List.of());
    }

    private void descriptor(String id, List<String> provides) throws IOException {
        String api = PACKAGE + "." + id + ".api";
        write("apis", id, out -> Descriptors.write(ApiDescriptor.builder(id).apiPackages(List.of(api))
                .apiVersion("1.0.0").provides(provides).build(), out));
        write("modules", id, out -> Descriptors.write(ModuleDescriptor.builder(id).packageName(PACKAGE + "." + id)
                .apiPackages(List.of(api)).apiVersion("1.0.0").provides(provides).build(), out));
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

    private void run(ClassLoader parent, Consumer<AssertableApplicationContext> check, String... properties)
            throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, parent)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("management.endpoints.web.exposure.include=topolith")
                    .withPropertyValues(properties).run(context -> {
                        assertNull(context.getStartupFailure());
                        check.accept(context);
                    });
        }
    }

    private void run(Consumer<AssertableApplicationContext> check, String... properties) throws Exception {
        run(RefreshTest.class.getClassLoader(), check, properties);
    }

    private static HttpServer stub(String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user/ping", exchange -> {
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

    /** Changes properties as a refresh would; a null value removes the key. */
    private static Set<String> set(AssertableApplicationContext context, String... pairs) {
        ConfigurableApplicationContext source = context
                .getSourceApplicationContext(ConfigurableApplicationContext.class);
        if (source.getEnvironment().getPropertySources().get("refreshed") == null) {
            source.getEnvironment().getPropertySources().addFirst(new MapPropertySource("refreshed", new HashMap<>()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) source.getEnvironment().getPropertySources()
                .get("refreshed").getSource();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
            keys.add(pairs[i]);
        }
        return keys;
    }

    /** Publishes the event Spring Cloud publishes for the given keys. */
    private static void publish(AssertableApplicationContext context, String... keys) {
        ConfigurableApplicationContext source = context
                .getSourceApplicationContext(ConfigurableApplicationContext.class);
        source.publishEvent(new EnvironmentChangeEvent(source, Set.of(keys)));
    }

    private static void refresh(AssertableApplicationContext context, String... pairs) {
        publish(context, set(context, pairs).toArray(String[]::new));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> report(AssertableApplicationContext context) {
        try {
            Object endpoint = context.getBean("topolithEndpoint");
            Method modules = endpoint.getClass().getDeclaredMethod("modules");
            modules.setAccessible(true);
            return (Map<String, Object>) modules.invoke(endpoint);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> module(AssertableApplicationContext context, String id) {
        return (Map<String, Object>) ((Map<String, Object>) report(context).get("modules")).get(id);
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test
    void ac_mode_13_a_changed_mode_logs_tl0010_and_reports_restart_pending(CapturedOutput output)
            throws Exception {
        HttpServer server = stub("pong");
        try {
            run(context -> {
                assertEquals(1, context.getBeansOfType(TopolithRefreshListener.class).size());
                refresh(context, "topolith.modules.user.mode", "none");
                String log = output.getAll();
                assertTrue(log.contains("TL0010"), log);
                assertTrue(log.contains("module 'user' runs in mode external and the refreshed configuration "
                        + "requests 'none'"), log);
                assertEquals(true, report(context).get("restartPending"));
                assertEquals("external", module(context, "user").get("mode"));
                assertEquals("none", module(context, "user").get("requestedMode"));
                assertEquals(List.of("mode"), module(context, "user").get("restartPending"));
                // the running topology and its contributions are unchanged (INV-9)
                assertEquals("pong", context.getBean(UserContract.class).ping());
            }, "topolith.modules.user.mode=external", "topolith.modules.payment.mode=none", USER_URL + "="
                    + url(server));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_mode_15_several_changed_modes_are_each_named_with_both_modes(CapturedOutput output)
            throws Exception {
        HttpServer server = stub("pong");
        try {
            run(context -> {
                refresh(context, "topolith.modules.user.mode", "none", "topolith.modules.payment.mode", "internal");
                String log = output.getAll();
                assertTrue(log.contains("module 'user' runs in mode external and the refreshed configuration "
                        + "requests 'none'"), log);
                assertTrue(log.contains("module 'payment' runs in mode none and the refreshed configuration "
                        + "requests 'internal'"), log);
                assertEquals(2, count(log, "TL0010"));
                assertEquals("internal", module(context, "payment").get("requestedMode"));
                refresh(context, "topolith.modules.user.mode", "external");
                assertEquals(true, report(context).get("restartPending"));
                assertFalse(module(context, "user").containsKey("requestedMode"));
                refresh(context, "topolith.modules.payment.mode", "none");
                assertEquals(false, report(context).get("restartPending"));
                assertFalse(module(context, "payment").containsKey("restartPending"));
            }, "topolith.modules.user.mode=external", "topolith.modules.payment.mode=none", USER_URL + "="
                    + url(server));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_mode_14_refreshing_a_changed_mode_keeps_contributions_and_running_mode() throws Exception {
        HttpServer server = stub("pong");
        try {
            run(context -> {
                UserContract before = context.getBean(UserContract.class);
                refresh(context, "topolith.modules.user.mode", "none");
                assertEquals("external", module(context, "user").get("mode"));
                assertSame(before, context.getBean(UserContract.class));
                assertEquals("pong", context.getBean(UserContract.class).ping());
            }, "topolith.modules.user.mode=external", "topolith.modules.payment.mode=none", USER_URL + "="
                    + url(server));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_mode_17_mode_restart_pending_clears_only_when_all_requested_modes_match() throws Exception {
        HttpServer server = stub("pong");
        try {
            run(context -> {
                refresh(context, "topolith.modules.user.mode", "none", "topolith.modules.payment.mode", "internal");
                refresh(context, "topolith.modules.user.mode", "external");
                assertEquals(true, report(context).get("restartPending"));
                refresh(context, "topolith.modules.payment.mode", "none");
                assertEquals(false, report(context).get("restartPending"));
                assertFalse(module(context, "user").containsKey("restartPending"));
                assertFalse(module(context, "payment").containsKey("restartPending"));
            }, "topolith.modules.user.mode=external", "topolith.modules.payment.mode=none", USER_URL + "="
                    + url(server));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_ext_22_switching_between_url_and_service_id_needs_a_restart(CapturedOutput output) throws Exception {
        HttpServer server = stub("pong");
        try {
            String instance = "spring.cloud.discovery.client.simple.instances.users-service[0].uri=" + url(server);
            run(context -> {
                refresh(context, USER_SERVICE, "users-service", USER_URL, "");
                assertTrue(output.getAll().contains("TL0010"), output.getAll());
                assertTrue(output.getAll().contains("external module 'user' was switched between"), output.getAll());
                assertEquals(true, report(context).get("restartPending"));
                assertEquals(List.of("wiring"), module(context, "user").get("restartPending"));
                // the running client keeps its wiring and no rebuild failed
                assertEquals("pong", context.getBean(UserContract.class).ping());
                assertFalse(module(context, "user").containsKey("refresh"));
                refresh(context, USER_URL, url(server), USER_SERVICE, "");
                assertEquals(false, report(context).get("restartPending"));
            }, "topolith.modules.user.mode=external", "topolith.modules.payment.mode=none", USER_URL + "="
                    + url(server), instance);
            // and from a service id to a URL
            run(context -> {
                refresh(context, USER_URL, url(server), USER_SERVICE, "");
                assertEquals(List.of("wiring"), module(context, "user").get("restartPending"));
                assertEquals("pong", context.getBean(UserContract.class).ping());
            }, "topolith.modules.user.mode=external", "topolith.modules.payment.mode=none", USER_SERVICE
                    + "=users-service", instance);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_ext_20_a_service_id_change_rebuilds_the_client() throws Exception {
        HttpServer first = stub("first");
        HttpServer second = stub("second");
        try {
            run(context -> {
                UserContract client = context.getBean(UserContract.class);
                assertEquals("first", client.ping());
                refresh(context, USER_SERVICE, "other-service");
                assertEquals("second", client.ping());
                assertEquals("lb://other-service/user", context.getEnvironment()
                        .getProperty("spring.http.serviceclient.user.base-url"));
                assertFalse(module(context, "user").containsKey("refresh"));
                assertEquals(false, report(context).get("restartPending"));
            }, "topolith.modules.user.mode=external", "topolith.modules.payment.mode=none", USER_SERVICE
                    + "=users-service", "spring.cloud.discovery.client.simple.instances.users-service[0].uri="
                    + url(first), "spring.cloud.discovery.client.simple.instances.other-service[0].uri="
                    + url(second));
        } finally {
            first.stop(0);
            second.stop(0);
        }
    }

    @Test
    void ac_mode_20_cloud_classes_with_the_integration_present_log_no_tl0014(CapturedOutput output) throws Exception {
        run(context -> {
            assertEquals(1, context.getBeansOfType(TopolithRefreshListener.class).size());
            assertFalse(output.getAll().contains("TL0014"), output.getAll());
        }, "topolith.modules.user.mode=none", "topolith.modules.payment.mode=none");
    }

    @Test
    void ac_ext_22_an_lb_url_counts_as_service_id_wiring_in_both_directions(CapturedOutput output)
            throws Exception {
        HttpServer server = stub("pong");
        try {
            String instance = "spring.cloud.discovery.client.simple.instances.users-service[0].uri=" + url(server);
            run(context -> {
                refresh(context, USER_URL, "lb://users-service");
                assertTrue(output.getAll().contains("TL0010"), output.getAll());
                assertEquals(List.of("wiring"), module(context, "user").get("restartPending"));
                assertEquals("pong", context.getBean(UserContract.class).ping());
                assertFalse(module(context, "user").containsKey("refresh"));
                refresh(context, USER_URL, url(server));
                assertEquals(false, report(context).get("restartPending"));
            }, "topolith.modules.user.mode=external", "topolith.modules.payment.mode=none", USER_URL + "="
                    + url(server), instance);
            run(context -> {
                refresh(context, USER_URL, url(server));
                assertEquals(List.of("wiring"), module(context, "user").get("restartPending"));
                assertEquals("pong", context.getBean(UserContract.class).ping());
                assertFalse(module(context, "user").containsKey("refresh"));
            }, "topolith.modules.user.mode=external", "topolith.modules.payment.mode=none", USER_URL
                    + "=lb://users-service", instance);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_mode_13_a_changed_standalone_key_reruns_the_mode_comparison(CapturedOutput output) throws Exception {
        HttpServer server = stub("pong");
        try {
            run(context -> {
                // payment has no declared mode: it is the standalone module, so it runs internal
                assertEquals("internal", module(context, "payment").get("mode"));
                set(context, "topolith.standalone", null, "topolith.modules.payment.mode", "none");
                publish(context, "topolith.standalone");
                assertEquals("none", module(context, "payment").get("requestedMode"));
                assertEquals(true, report(context).get("restartPending"));
                assertTrue(output.getAll().contains("module 'payment' runs in mode internal"), output.getAll());
            }, "topolith.standalone=payment", "topolith.modules.user.mode=external", USER_URL + "=" + url(server));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void r_mode_05_without_spring_cloud_there_is_no_refresh_handling() throws Exception {
        run(new FilteredClassLoader("org.springframework.cloud.context"), context -> {
            assertTrue(context.getBeansOfType(TopolithRefreshListener.class).isEmpty());
            assertEquals(1, context.getBeansOfType(io.github.rey5137.topolith.spring.boot.TopolithRefresh.class)
                    .size());
        }, "topolith.modules.user.mode=none", "topolith.modules.payment.mode=none");
    }
}
