package io.github.rey5137.topolith.spring.cloud;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.cloud.fixture.host.Host;
import io.github.rey5137.topolith.spring.cloud.fixture.user.api.UserContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2.2 acceptance tests: a service id resolves through Spring Cloud LoadBalancer and a DiscoveryClient. */
class DiscoveryTest {
    private static final String API = UserContract.class.getPackageName();
    private static final String PACKAGE = "io.github.rey5137.topolith.spring.cloud.fixture.user";

    @TempDir
    Path root;

    @BeforeEach
    void descriptors() throws IOException {
        String contract = UserContract.class.getName();
        write("apis", out -> Descriptors.write(ApiDescriptor.builder("user").apiPackages(List.of(API))
                .apiVersion("1.0.0").provides(List.of(contract)).build(), out));
        write("modules", out -> Descriptors.write(ModuleDescriptor.builder("user").packageName(PACKAGE)
                .apiPackages(List.of(API)).apiVersion("1.0.0").provides(List.of(contract)).build(), out));
    }

    private void write(String kind, Body body) throws IOException {
        Path file = root.resolve("META-INF/topolith/" + kind + "/user.properties");
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            body.write(out);
        }
    }

    private interface Body {
        void write(Writer out) throws IOException;
    }

    private void run(Consumer<AssertableApplicationContext> check, String... properties) throws Exception {
        run(DiscoveryTest.class.getClassLoader(), check, properties);
    }

    private void run(ClassLoader parent, Consumer<AssertableApplicationContext> check, String... properties)
            throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, parent)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues(properties).run(context -> check.accept(context));
        }
    }

    private static HttpServer stub() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user/ping", exchange -> {
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void ac_ext_05_service_id_resolves_through_the_discovery_client() throws Exception {
        HttpServer server = stub();
        try {
            run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals("pong", context.getBean(UserContract.class).ping());
            }, "topolith.modules.user.mode=external", "topolith.modules.user.remote.service-id=users-service",
                    "spring.cloud.discovery.client.simple.instances.users-service[0].uri=http://127.0.0.1:"
                            + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_ext_05_service_id_conflicting_with_a_boot_base_url_fails() throws Exception {
        run(context -> {
            Throwable cause = context.getStartupFailure();
            while (cause != null && !(cause instanceof TopolithException)) {
                cause = cause.getCause();
            }
            assertNotNull(cause);
            TopolithException failure = (TopolithException) cause;
            assertEquals(List.of(TopolithCode.TL0003), failure.diagnostics().stream().map(d -> d.code()).toList());
            assertTrue(failure.getMessage().contains("topolith.modules.user.remote.service-id"), failure.getMessage());
            assertTrue(failure.getMessage().contains("spring.http.serviceclient.user.base-url"), failure.getMessage());
        }, "topolith.modules.user.mode=external", "topolith.modules.user.remote.service-id=users-service",
                "spring.http.serviceclient.user.base-url=http://localhost:1");
        // the same service id on Boot's key is no conflict
        run(context -> assertNull(context.getStartupFailure()), "topolith.modules.user.mode=external",
                "topolith.modules.user.remote.service-id=users-service",
                "spring.http.serviceclient.user.base-url=lb://users-service");
    }

    @Test
    void ac_ext_06_commons_without_a_loadbalancer_implementation_fails() throws Exception {
        run(new FilteredClassLoader("org.springframework.cloud.loadbalancer"), context -> {
            Throwable cause = context.getStartupFailure();
            while (cause != null && !(cause instanceof TopolithException)) {
                cause = cause.getCause();
            }
            assertNotNull(cause);
            TopolithException failure = (TopolithException) cause;
            assertEquals(List.of(TopolithCode.TL0009), failure.diagnostics().stream().map(d -> d.code()).toList());
            assertTrue(failure.getMessage().contains("spring-cloud-starter-loadbalancer"), failure.getMessage());
        }, "topolith.modules.user.mode=external", "topolith.modules.user.remote.service-id=users-service");
    }

    @Test
    void ac_ext_05_blank_url_with_a_service_id_is_a_service_id_module() throws Exception {
        HttpServer server = stub();
        try {
            run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals("pong", context.getBean(UserContract.class).ping());
            }, "topolith.modules.user.mode=external", "topolith.modules.user.remote.url=  ",
                    "topolith.modules.user.remote.service-id=users-service",
                    "spring.http.serviceclient.user.base-url=lb://users-service",
                    "spring.cloud.discovery.client.simple.instances.users-service[0].uri=http://127.0.0.1:"
                            + server.getAddress().getPort());
            run(context -> assertEquals("lb://users-service",
                    context.getEnvironment().getProperty("spring.http.serviceclient.user.base-url")),
                    "topolith.modules.user.mode=external", "topolith.modules.user.remote.url=",
                    "topolith.modules.user.remote.service-id=users-service");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_mode_09_none_and_internal_modules_ignore_a_service_id() throws Exception {
        for (String mode : List.of("none", "internal")) {
            run(context -> {
                assertNull(context.getStartupFailure());
                assertTrue(context.getBeansOfType(UserContract.class).isEmpty());
                assertNull(context.getEnvironment().getProperty("spring.http.serviceclient.user.base-url"));
            }, "topolith.modules.user.mode=" + mode, "topolith.modules.user.remote.service-id=users-service");
        }
    }
}
