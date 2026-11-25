package io.github.rey5137.topolith.spring.cloud;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;
import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.spring.cloud.fixture.host.Host;
import io.github.rey5137.topolith.spring.cloud.fixture.user.api.UserContract;
import io.github.rey5137.topolith.spring.cloud.fixture.user.api.UserNotFoundException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** D-51: with Spring Cloud CircuitBreaker on an external group, translation sees through one wrapping level. */
class CircuitBreakerTranslationTest {
    private static final String PACKAGE = "io.github.rey5137.topolith.spring.cloud.fixture";

    @TempDir
    Path root;

    /** Two failed calls open the circuit. */
    @Configuration(proxyBeanMethods = false)
    static class Breaker {
        @Bean
        Customizer<Resilience4JCircuitBreakerFactory> breaker() {
            return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(CircuitBreakerConfig.custom().slidingWindowSize(2)
                            .minimumNumberOfCalls(2).failureRateThreshold(50).build())
                    .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(10)).build())
                    .build());
        }
    }

    /** A time limiter shorter than the stub's delay. */
    @Configuration(proxyBeanMethods = false)
    static class ShortTimeLimit {
        @Bean
        Customizer<Resilience4JCircuitBreakerFactory> breaker() {
            return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                    .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(200)).build())
                    .build());
        }
    }

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

    private void run(String url, Consumer<UserContract> check) throws Exception {
        run(Breaker.class, url, check);
    }

    private void run(Class<?> breaker, String url, Consumer<UserContract> check) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()},
                CircuitBreakerTranslationTest.class.getClassLoader())) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class, breaker)
                    .withPropertyValues("topolith.modules.user.mode=external", "topolith.modules.payment.mode=none",
                            "topolith.modules.user.remote.url=" + url,
                            "topolith.modules.user.remote.read-timeout=2s",
                            "spring.cloud.circuitbreaker.http-services.enabled=true")
                    .run((AssertableApplicationContext context) -> {
                        assertNull(context.getStartupFailure());
                        check.accept(context.getBean(UserContract.class));
                    });
        }
    }

    private static HttpServer stub(int status, String type, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void ac_ext_09_a_declared_exception_is_rebuilt_through_the_circuit_breaker() throws Exception {
        String problem = "{\"type\":\"urn:topolith:problem:contract-exception\",\"status\":500,\"detail\":\"no such "
                + "user\",\"exception\":\"" + UserNotFoundException.class.getName() + "\",\"module\":\"user\"}";
        HttpServer server = stub(500, "application/problem+json", problem);
        try {
            run("http://127.0.0.1:" + server.getAddress().getPort(), user -> {
                UserNotFoundException e = assertThrows(UserNotFoundException.class, user::ping);
                assertEquals("no such user", e.getMessage());
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_ext_10_a_refused_connection_is_unavailable_through_the_circuit_breaker() throws Exception {
        run("http://127.0.0.1:" + freePort(), user -> {
            ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class, user::ping);
            assertEquals("user", e.moduleId());
            assertEquals("module 'user' could not be reached (connection failure)", e.getMessage());
        });
    }

    @Test
    void ac_ext_10_b_an_open_circuit_is_unavailable() throws Exception {
        run("http://127.0.0.1:" + freePort(), user -> {
            assertThrows(ModuleUnavailableException.class, user::ping);
            assertThrows(ModuleUnavailableException.class, user::ping);
            ModuleUnavailableException open = assertThrows(ModuleUnavailableException.class, user::ping);
            assertEquals("module 'user' could not be reached (circuit open)", open.getMessage());
            assertInstanceOf(RuntimeException.class, open.getCause());
        });
    }

    @Test
    void ac_ext_10_c_a_time_limiter_timeout_is_unavailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            run(ShortTimeLimit.class, "http://127.0.0.1:" + server.getAddress().getPort(), user -> {
                ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class, user::ping);
                assertEquals("module 'user' could not be reached (timeout)", e.getMessage());
            });
        } finally {
            server.stop(0);
        }
    }
}
