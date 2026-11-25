package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountNotFoundException;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.payment.api.PaymentDeclinedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2.3 provider half: a module route answers a declared exception as a Topolith problem (R-EXT-04, D-43). */
class ProviderErrorsTest {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        ContractExceptionsTest.contracts(root, "account", AccountContract.class);
        ContractExceptionsTest.contracts(root, "payment");
    }

    /** A web host on a random port with the account module in {@code mode}. */
    private void serve(String mode, Consumer<AssertableWebApplicationContext> check) throws Exception {
        serve(root, mode, check);
    }

    /** A web host on a random port, serving the account module of {@code root} in {@code mode}. */
    static void serve(Path root, String mode, Consumer<AssertableWebApplicationContext> check) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                            "topolith.modules.account.mode=" + mode, "topolith.modules.payment.mode=none",
                            "topolith.modules.account.expose.enabled=" + mode.equals("internal"),
                            "topolith.modules.account.remote.url=http://localhost:1")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        check.accept(context);
                    });
        }
    }

    record Answer(int status, String contentType, String body) {
    }

    static int port(AssertableWebApplicationContext context) {
        return context.getSourceApplicationContext(ServletWebServerApplicationContext.class).getWebServer().getPort();
    }

    static Answer get(AssertableWebApplicationContext context, String path) {
        int port = port(context);
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + path)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                    response.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertProblem(AssertableWebApplicationContext context, String failure, int status,
            Class<?> exception, String module, String detail) {
        Answer answer = get(context, "/account/find?case=" + failure);
        assertEquals(status, answer.status(), answer.body());
        assertTrue(answer.contentType().startsWith("application/problem+json"), answer.contentType());
        Map<String, Object> body = context.getBean(JsonMapper.class).readValue(answer.body(), Map.class);
        assertEquals(ModuleExceptionResolver.TYPE, body.get("type"));
        assertEquals(status, body.get("status"));
        assertEquals(detail, body.get("detail"));
        assertEquals(exception.getName(), body.get("exception"));
        assertEquals(module, body.get("module"));
    }

    @Test
    void ac_ext_09_declared_exception_is_a_topolith_problem() throws Exception {
        serve("internal", context -> {
            assertEquals("found", get(context, "/account/find?case=ok").body());
            assertProblem(context, "missing", 404, AccountNotFoundException.class, "account", "no such account");
            // a subclass outside the api packages is sent as its declared ancestor
            assertProblem(context, "locked", 500, AccountException.class, "account", "account is locked");
            // another module's declared exception passing through is declared too
            assertProblem(context, "declined", 402, PaymentDeclinedException.class, "account", "card declined");
            // a provider's own failed call reaches its caller as the module it could not reach
            // with a fixed detail: the exception's own message stays with the provider
            assertProblem(context, "unavailable", 503, ModuleUnavailableException.class, "payment",
                    "module 'payment' could not be reached (unavailable downstream)");
        });
    }

    @Test
    void ac_ext_09_a_partial_answer_is_replaced_by_the_problem() throws Exception {
        serve("internal", context -> {
            int port = port(context);
            try {
                HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + port + "/account/partial")).timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(500, response.statusCode(), response.body());
                assertEquals("application/problem+json", response.headers().firstValue("Content-Type").orElse(""));
                assertEquals("kept", response.headers().firstValue("X-Trace").orElse(""));
                assertFalse(response.body().contains("partial\":"), response.body());
                assertTrue(response.body().contains(AccountException.class.getName()), response.body());
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void ac_ext_29_undeclared_exception_is_left_to_the_host_and_its_message_is_not_sent() throws Exception {
        serve("internal", context -> {
            for (String failure : new String[] {"secret", "wrapped"}) {
                Answer answer = get(context, "/account/find?case=" + failure);
                // the host decides: Boot's error page here, the module's own handler for the wrapped cause
                assertEquals(failure.equals("secret") ? 500 : 418, answer.status());
                assertFalse(answer.body().contains("secret"), answer.body());
                assertFalse(answer.body().contains(ModuleExceptionResolver.TYPE), answer.body());
                // only the top-level exception counts, never its cause
                assertFalse(answer.body().contains(AccountException.class.getName()), answer.body());
            }
        });
    }

    @Test
    void ac_ext_32_host_and_controller_advices_do_not_change_module_routes() throws Exception {
        serve("internal", context -> {
            assertProblem(context, "missing", 404, AccountNotFoundException.class, "account", "no such account");
            assertProblem(context, "locked", 500, AccountException.class, "account", "account is locked");
        });
    }

    @Test
    void ac_ext_31_host_routes_keep_their_advice() throws Exception {
        serve("internal", context -> {
            Answer answer = get(context, "/host/fail");
            assertEquals(418, answer.status());
            assertEquals("host advice", answer.body());
        });
    }

    @Test
    void ac_ext_31_resolver_only_with_an_internal_module_in_a_servlet_application() throws Exception {
        serve("internal", context -> assertTrue(installed(context)));
        for (String mode : new String[] {"none", "external"}) {
            serve(mode, context -> {
                assertTrue(context.getBeansOfType(ModuleErrorsConfiguration.class).isEmpty());
                assertFalse(installed(context));
                assertEquals(418, get(context, "/host/fail").status());
            });
        }
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            ApplicationContextRunner plain = new ApplicationContextRunner().withClassLoader(loader)
                    .withUserConfiguration(ErrorHost.class).withPropertyValues("spring.liquibase.enabled=false",
                            "topolith.modules.account.mode=internal", "topolith.modules.payment.mode=none");
            // not a web application
            plain.run(context -> {
                assertNull(context.getStartupFailure());
                assertTrue(context.getBeansOfType(ModuleErrorsConfiguration.class).isEmpty());
            });
        }
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(
                new FilteredClassLoader("org.springframework.web.servlet"), root)) {
            new WebApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.account.mode=internal",
                            "topolith.modules.payment.mode=none")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertTrue(context.getBeansOfType(ModuleErrorsConfiguration.class).isEmpty());
                    });
        }
    }

    private static boolean installed(AssertableWebApplicationContext context) {
        return context.getBeansOfType(HandlerExceptionResolverComposite.class).values().stream()
                .flatMap(composite -> composite.getExceptionResolvers().stream())
                .anyMatch(ModuleExceptionResolver.class::isInstance);
    }
}
