package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.Host;
import io.github.rey5137.topolith.spring.boot.fixture.local.LocalComponent;
import io.github.rey5137.topolith.spring.boot.fixture.local.api.LocalContract;
import io.github.rey5137.topolith.spring.boot.fixture.off.api.OffContract;
import io.github.rey5137.topolith.spring.boot.fixture.remote.RemoteComponent;
import io.github.rey5137.topolith.spring.boot.fixture.remote.api.MethodContract;
import io.github.rey5137.topolith.spring.boot.fixture.plain.api.PlainContract;
import io.github.rey5137.topolith.spring.boot.fixture.remote.api.RemoteContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.service.registry.HttpServiceProxyRegistry;

import static io.github.rey5137.topolith.spring.boot.Fixtures.BASE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2.1 acceptance tests: HTTP service groups, the fixed URL and timeouts of external modules. */
class ExternalClientTest {
    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        contracts("remote", RemoteContract.class, MethodContract.class);
        contracts("plain", PlainContract.class);
        contracts("local", LocalContract.class);
        contracts("off", OffContract.class);
    }

    /** The API descriptor and the module descriptor of {@code id}, both listing the same contracts. */
    private void contracts(String id, Class<?>... contracts) throws IOException {
        String apiPackage = BASE + "." + id + ".api";
        List<String> names = java.util.Arrays.stream(contracts).map(Class::getName).toList();
        write("apis", id, out -> Descriptors.write(ApiDescriptor.builder(id).apiPackages(List.of(apiPackage))
                .apiVersion("1.0.0").provides(names).build(), out));
        write("modules", id, out -> Descriptors.write(ModuleDescriptor.builder(id).packageName(BASE + "." + id)
                .apiPackages(List.of(apiPackage)).apiVersion("1.0.0").provides(names).build(), out));
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

    private void run(Consumer<AssertableApplicationContext> check, String... properties) throws Exception {
        run(Fixtures.class.getClassLoader(), check, properties);
    }

    private void run(ClassLoader parent, Consumer<AssertableApplicationContext> check, String... properties)
            throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(parent, root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.local.mode=internal",
                            "topolith.modules.off.mode=none", "topolith.modules.plain.mode=none",
                            "topolith.modules.remote.mode=none")
                    .withPropertyValues(properties).run(context -> check.accept(context));
        }
    }

    @Test
    void ac_ext_01_and_ac_mode_09_external_contract_is_a_client_and_nothing_else() throws Exception {
        run(context -> {
            assertNull(context.getStartupFailure());
            RemoteContract client = context.getBean(RemoteContract.class);
            assertTrue(java.lang.reflect.Proxy.isProxyClass(client.getClass()));
            assertEquals(client, context.getBean(HttpServiceProxyRegistry.class).getClient("remote",
                    RemoteContract.class));
            assertEquals(1, context.getBeansOfType(RemoteContract.class).size());
            // only @HttpExchange contracts are registered, whether on the type or on a method
            assertEquals(1, context.getBeansOfType(MethodContract.class).size());
            assertTrue(context.getBeansOfType(RemoteComponent.class).isEmpty());
            List<String> moduleBeans = new java.util.ArrayList<>();
            for (String name : context.getBeanDefinitionNames()) {
                Class<?> type = context.getType(name);
                if (type != null && !type.isInterface() && type.getName().startsWith(BASE + ".remote")) {
                    moduleBeans.add(name + ":" + type.getName());
                }
            }
            assertEquals(List.of(), moduleBeans);
        }, "topolith.modules.remote.mode=external", "topolith.modules.remote.remote.url=http://localhost:1");
    }

    @Test
    void ac_mode_09_internal_and_none_modules_get_no_group_or_client() throws Exception {
        run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(LocalContract.class).size());
            assertTrue(context.getBean(LocalContract.class) instanceof LocalComponent);
            assertTrue(context.getBeansOfType(OffContract.class).isEmpty());
            HttpServiceProxyRegistry registry = context.getBean(HttpServiceProxyRegistry.class);
            assertTrue(registry.getGroupNames().contains("remote"));
            assertFalse(registry.getGroupNames().contains("local"));
            assertFalse(registry.getGroupNames().contains("off"));
        }, "topolith.modules.remote.mode=external", "topolith.modules.remote.remote.url=http://localhost:1");
        run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(HttpServiceProxyRegistry.class).isEmpty());
            assertTrue(context.getBeansOfType(RemoteContract.class).isEmpty());
        }, "topolith.modules.remote.mode=none");
    }

    @Test
    void ac_ext_04_fixed_url_handles_a_contract_call() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/remote/ping", exchange -> {
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals("pong", context.getBean(RemoteContract.class).ping());
            }, "topolith.modules.remote.mode=external",
                    "topolith.modules.remote.remote.url=http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_ext_08_and_ac_ext_07_timeouts_override_and_default() throws Exception {
        String external = "topolith.modules.remote.mode=external";
        String url = "topolith.modules.remote.remote.url=http://localhost:1";
        run(context -> {
            var settings = context.getBean(HttpServiceClientProperties.class).get("remote");
            assertEquals(Duration.ofSeconds(2), settings.getConnectTimeout());
            assertEquals(Duration.ofSeconds(5), settings.getReadTimeout());
            assertEquals("http://localhost:1/remote", settings.getBaseUrl());
        }, external, url);
        run(context -> {
            var settings = context.getBean(HttpServiceClientProperties.class).get("remote");
            assertEquals(Duration.ofSeconds(3), settings.getConnectTimeout());
            assertEquals(Duration.ofSeconds(9), settings.getReadTimeout());
        }, external, url, "topolith.modules.remote.remote.connect-timeout=3s",
                "spring.http.serviceclient.remote.read-timeout=9s");
    }

    @Test
    void ac_ext_07_boot_global_timeouts_beat_the_defaults() throws Exception {
        run(context -> {
            assertNull(context.getStartupFailure());
            assertNull(context.getEnvironment().getProperty("spring.http.serviceclient.remote.read-timeout"));
            assertEquals("2s", context.getEnvironment()
                    .getProperty("spring.http.serviceclient.remote.connect-timeout"));
            assertEquals(Duration.ofSeconds(30), context.getBean(HttpClientSettings.class).readTimeout());
        }, "topolith.modules.remote.mode=external", "topolith.modules.remote.remote.url=http://localhost:1",
                "spring.http.clients.read-timeout=30s");
    }

    private void assertTl0026(AssertableApplicationContext context, String... expected) {
        assertFailure(TopolithCode.TL0026, context, expected);
    }

    private void assertFailure(TopolithCode code, AssertableApplicationContext context, String... expected) {
        Throwable cause = context.getStartupFailure();
        while (cause != null && !(cause instanceof TopolithException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause);
        TopolithException failure = (TopolithException) cause;
        assertEquals(List.of(code), failure.diagnostics().stream().map(d -> d.code()).distinct()
                .toList());
        for (String text : expected) {
            assertTrue(failure.getMessage().contains(text), failure.getMessage());
        }
    }

    @Test
    void ac_ext_26_missing_client_stack_fails() throws Exception {
        String[] external = {"topolith.modules.remote.mode=external",
                "topolith.modules.remote.remote.url=http://localhost:1"};
        run(new FilteredClassLoader("org.springframework.web.service"),
                context -> assertTl0026(context, "remote", "spring-web", "spring-boot-starter-restclient"), external);
        run(new FilteredClassLoader("org.springframework.boot.restclient"),
                context -> assertTl0026(context, "remote", "auto-configuration", "spring-boot-starter-restclient"),
                external);
    }

    @Test
    void ac_ext_06_service_id_without_cloud_loadbalancer_fails_with_the_starters() throws Exception {
        String[] service = {"topolith.modules.remote.mode=external", "topolith.modules.remote.remote.service-id=users"};
        run(new FilteredClassLoader("org.springframework.cloud"), context -> assertFailure(TopolithCode.TL0009,
                context, "'remote'", "remote.service-id", "spring-cloud-starter-loadbalancer"), service);
        // a fixed URL never needs Cloud, and a module that is not external ignores its service id
        run(new FilteredClassLoader("org.springframework.cloud"), context -> assertNull(context.getStartupFailure()),
                "topolith.modules.remote.mode=external", "topolith.modules.remote.remote.url=http://localhost:1");
        run(new FilteredClassLoader("org.springframework.cloud"), context -> assertNull(context.getStartupFailure()),
                "topolith.modules.local.remote.service-id=users");
    }

    @Test
    void ac_ext_27_unloadable_contract_fails() throws Exception {
        write("apis", "ghost", out -> Descriptors.write(ApiDescriptor.builder("ghost")
                .apiPackages(List.of(BASE + ".ghost.api")).apiVersion("1.0.0")
                .provides(List.of(BASE + ".ghost.api.Missing")).build(), out));
        run(context -> assertTl0026(context, "ghost", BASE + ".ghost.api.Missing"),
                "topolith.modules.ghost.mode=external", "topolith.modules.ghost.remote.url=http://localhost:1");
    }

    @Test
    void ac_ext_28_contract_without_http_exchange_fails() throws Exception {
        run(context -> assertTl0026(context, "plain", PlainContract.class.getName()),
                "topolith.modules.plain.mode=external", "topolith.modules.plain.remote.url=http://localhost:1");
    }
}
