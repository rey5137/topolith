package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;
import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountCheckedException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountFailure;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountNotFoundException;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ClientHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.HostClient;
import io.github.rey5137.topolith.spring.boot.errorfixture.payment.api.PaymentDeclinedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import static io.github.rey5137.topolith.spring.boot.Fixtures.ERRORS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2.3 caller half: an external module's proxy rebuilds declared exceptions and maps transport failures. */
class CallerErrorsTest {
    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        ContractExceptionsTest.contracts(root, "account", AccountContract.class);
        ContractExceptionsTest.contracts(root, "payment");
    }

    /** A caller host with the account module external at {@code url}. */
    static void call(Path root, String url, Consumer<AssertableApplicationContext> check, String... properties)
            throws Exception {
        call(root, (context, loader) -> check.accept(context), "topolith.modules.account.mode=external",
                "topolith.modules.account.remote.url=" + url, properties);
    }

    private interface Check {
        void accept(AssertableApplicationContext context, Fixtures.RecordingLoader loader);
    }

    private static void call(Path root, Check check, String mode, String url, String... properties)
            throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ClientHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.payment.mode=none",
                            mode, url, "topolith.modules.account.remote.read-timeout=1s")
                    .withPropertyValues(properties).run(context -> {
                        assertNull(context.getStartupFailure());
                        check.accept(context, loader);
                    });
        }
    }

    /** A stub provider answering every request with {@code status}, {@code type} and {@code body}. */
    private static HttpServer stub(int status, String type, String body, long delayMillis) throws IOException {
        return stub(status, type, body, delayMillis, false);
    }

    private static HttpServer stub(int status, String type, String body, long delayMillis, boolean moduleHeader)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (moduleHeader) {
                exchange.getResponseHeaders().add("Topolith-Module", "account");
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String problem(String type, String exception, String module) {
        return "{\"type\":\"" + type + "\",\"status\":500,\"detail\":\"spoofed\",\"exception\":\"" + exception
                + "\",\"module\":\"" + module + "\"}";
    }

    @Test
    void ac_ext_09_declared_exception_round_trips_to_the_same_type() throws Exception {
        ProviderErrorsTest.serve(root, "internal", provider -> {
            try {
                call(root, "http://127.0.0.1:" + ProviderErrorsTest.port(provider), context -> {
                    AccountContract account = context.getBean(AccountContract.class);
                    assertEquals("found", account.find("ok"));
                    AccountNotFoundException missing = assertThrows(AccountNotFoundException.class,
                            () -> account.find("missing"));
                    assertEquals("no such account", missing.getMessage());
                    assertInstanceOf(RestClientResponseException.class, missing.getSuppressed()[0]);
                    assertNull(missing.getCause());
                    // a subclass outside the api packages comes back as its declared ancestor
                    RuntimeException locked = assertThrows(RuntimeException.class, () -> account.find("locked"));
                    assertSame(AccountException.class, locked.getClass());
                    assertEquals("account is locked", locked.getMessage());
                    // a handler that started its answer before throwing
                    assertEquals("after a partial answer", assertThrows(AccountException.class,
                            account::partial).getMessage());
                    // another module's declared exception passing through the provider
                    assertEquals("card declined", assertThrows(PaymentDeclinedException.class,
                            () -> account.find("declined")).getMessage());
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void ac_ext_70_checked_exception_round_trips_over_http() throws Exception {
        ProviderErrorsTest.serve(root, "internal", provider -> {
            try {
                call(root, "http://127.0.0.1:" + ProviderErrorsTest.port(provider), context -> {
                    AccountContract account = context.getBean(AccountContract.class);
                    for (String value : List.of("direct", "subclass")) {
                        AccountCheckedException failure = assertThrows(AccountCheckedException.class,
                                () -> account.checked(value));
                        assertEquals(value.equals("direct") ? "checked message" : "checked subclass",
                                failure.getMessage());
                    }
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void ac_ext_72_and_74_bare_statuses_without_module_header_are_unavailable() throws Exception {
        for (int status : new int[] {401, 403, 404, 405, 415}) {
            HttpServer server = stub(status, "text/plain", "", 0);
            try {
                call(root, url(server), context -> {
                    ModuleUnavailableException failure = assertThrows(ModuleUnavailableException.class,
                            () -> context.getBean(AccountContract.class).find("ok"));
                    assertEquals("module 'account' could not be reached (status " + status + ")", failure.getMessage());
                    assertInstanceOf(RestClientResponseException.class, failure.getCause());
                });
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void ac_ext_72_module_header_and_other_statuses_leave_the_response_unspecified() throws Exception {
        for (int status : new int[] {401, 403, 404, 405, 415, 400, 409, 500}) {
            boolean header = status < 400 || List.of(401, 403, 404, 405, 415).contains(status);
            HttpServer server = stub(status, "text/plain", "", 0, header);
            try {
                call(root, url(server), context -> assertThrows(RestClientResponseException.class,
                        () -> context.getBean(AccountContract.class).find("ok")));
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void ac_ext_73_jdk_exceptions_round_trip_over_http() throws Exception {
        ProviderErrorsTest.serve(root, "internal", provider -> {
            try {
                call(root, "http://127.0.0.1:" + ProviderErrorsTest.port(provider), context -> {
                    AccountContract account = context.getBean(AccountContract.class);
                    assertThrows(IllegalArgumentException.class, () -> account.find("jdk-argument"));
                    assertThrows(IllegalStateException.class, () -> account.find("jdk-state"));
                    assertThrows(UnsupportedOperationException.class, () -> account.find("jdk-unsupported"));
                    assertSame(IllegalArgumentException.class,
                            assertThrows(IllegalArgumentException.class, () -> account.find("jdk-number")).getClass());
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void ac_ext_10_transitive_module_unavailable_names_the_unreachable_module() throws Exception {
        ProviderErrorsTest.serve(root, "internal", provider -> {
            try {
                call(root, "http://127.0.0.1:" + ProviderErrorsTest.port(provider), context -> {
                    ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                            () -> context.getBean(AccountContract.class).find("unavailable"));
                    assertEquals("payment", e.moduleId());
                    assertEquals("module 'payment' could not be reached (unavailable downstream)", e.getMessage());
                    assertInstanceOf(RestClientResponseException.class, e.getCause());
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void ac_ext_29_undeclared_exception_is_not_rebuilt_and_its_message_does_not_reach_the_caller() throws Exception {
        ProviderErrorsTest.serve(root, "internal", provider -> {
            try {
                call(root, "http://127.0.0.1:" + ProviderErrorsTest.port(provider), context -> {
                    RuntimeException e = assertThrows(RuntimeException.class,
                            () -> context.getBean(AccountContract.class).find("secret"));
                    assertFalse(e instanceof AccountFailure || e instanceof ModuleUnavailableException, e.toString());
                    assertFalse(String.valueOf(e.getMessage()).contains("secret"), e.getMessage());
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void ac_ext_10_connection_refused_is_module_unavailable() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        call(root, "http://127.0.0.1:" + port, context -> {
            ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                    () -> context.getBean(AccountContract.class).find("ok"));
            assertEquals("account", e.moduleId());
            assertEquals("module 'account' could not be reached (connection failure)", e.getMessage());
            assertInstanceOf(ResourceAccessException.class, e.getCause());
        });
    }

    @Test
    void ac_ext_10_read_timeout_is_module_unavailable() throws Exception {
        HttpServer server = stub(200, "text/plain", "late", 3000);
        try {
            call(root, url(server), context -> {
                ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                        () -> context.getBean(AccountContract.class).find("ok"));
                assertEquals("account", e.moduleId());
                assertEquals("module 'account' could not be reached (timeout)", e.getMessage());
                assertInstanceOf(ResourceAccessException.class, e.getCause());
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_ext_10_gateway_statuses_without_a_topolith_problem_are_module_unavailable() throws Exception {
        for (int status : new int[] {502, 503, 504}) {
            HttpServer server = stub(status, "text/plain", "gateway body", 0);
            try {
                call(root, url(server), context -> {
                    ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                            () -> context.getBean(AccountContract.class).find("ok"));
                    assertEquals("account", e.moduleId());
                    // MUE is declared and travels on: neither the downstream body nor the URL is in its message
                    assertEquals("module 'account' could not be reached (status " + status + ")", e.getMessage());
                    assertFalse(e.getMessage().contains("gateway body") || e.getMessage().contains("127.0.0.1"));
                    assertInstanceOf(RestClientResponseException.class, e.getCause());
                });
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void ac_ext_29_other_failures_propagate_unspecified() throws Exception {
        String topolith = ModuleExceptionResolver.TYPE;
        String declared = AccountException.class.getName();
        String internal = ERRORS + ".account.LockedAccountException";
        List<HttpServer> servers = List.of(
                stub(500, "text/plain", "boom", 0),
                stub(400, "application/json", "{}", 0),
                // a problem of another type, even one naming a declared class
                stub(500, "application/problem+json", problem("about:blank", declared, "account"), 0),
                // a name outside every known api package: the module's own package
                stub(500, "application/problem+json", problem(topolith, internal, "account"), 0),
                // a malformed name and an invalid module id on a propagated ModuleUnavailableException
                stub(500, "application/problem+json", problem(topolith, "..x", "account"), 0),
                stub(503, "application/problem+json", problem(topolith, ModuleUnavailableException.class.getName(),
                        "Bad Id"), 0));
        try {
            for (HttpServer server : servers) {
                call(root, (context, loader) -> {
                    RuntimeException e = assertThrows(RuntimeException.class,
                            () -> context.getBean(AccountContract.class).find("ok"));
                    assertInstanceOf(RestClientResponseException.class, e);
                    // a module-internal class named by a body is never loaded (AC-MODE-06)
                    assertEquals(List.of(), loader.requested(internal));
                }, "topolith.modules.account.mode=external", "topolith.modules.account.remote.url=" + url(server));
            }
        } finally {
            servers.forEach(server -> server.stop(0));
        }
    }

    @Test
    void ac_ext_31_only_external_groups_are_advised() throws Exception {
        HttpServer server = stub(503, "text/plain", "down", 0);
        try {
            call(root, url(server), context -> {
                assertEquals(1, context.getBeansOfType(ExternalErrorsConfiguration.class).size());
                assertThrows(ModuleUnavailableException.class,
                        () -> context.getBean(AccountContract.class).find("ok"));
                // the host's own group keeps Spring's exception
                assertInstanceOf(RestClientResponseException.class, assertThrows(RuntimeException.class,
                        () -> context.getBean(HostClient.class).status()));
            }, "spring.http.serviceclient.host.base-url=" + url(server));
            for (String mode : new String[] {"internal", "none"}) {
                call(root, (context, loader) -> {
                    assertTrue(context.getBeansOfType(ExternalErrorsConfiguration.class).isEmpty(), mode);
                    assertInstanceOf(RestClientResponseException.class, assertThrows(RuntimeException.class,
                            () -> context.getBean(HostClient.class).status()));
                }, "topolith.modules.account.mode=" + mode, "spring.http.serviceclient.host.base-url=" + url(server));
            }
        } finally {
            server.stop(0);
        }
    }
}
