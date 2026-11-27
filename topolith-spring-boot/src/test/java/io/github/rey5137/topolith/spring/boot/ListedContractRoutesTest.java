package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ClientHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ThreadTenant;
import io.github.rey5137.topolith.spring.boot.errorfixture.listed.api.ListedContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.listed.api.ListedFailure;
import io.github.rey5137.topolith.spring.boot.errorfixture.other.api.OtherContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.response.api.ResponseContract;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aot.generate.DefaultGenerationContext;
import org.springframework.aot.generate.InMemoryGeneratedFiles;
import org.springframework.aot.generate.NameGenerator;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.javapoet.ClassName;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.service.annotation.PostExchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListedContractRoutesTest {
    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        ContractExceptionsTest.contracts(root, "listed", ListedContract.class);
    }

    @AfterEach
    void clean() {
        ThreadTenant.set(null);
    }

    record Holder(ListedContract contract) {
    }

    @Configuration(proxyBeanMethods = false)
    static class Holders {
        @Bean
        Holder holder(ListedContract contract) {
            return new Holder(contract);
        }
    }

    private WebApplicationContextRunner server(Fixtures.RecordingLoader loader, CallObservationTest.Recorder recorder,
            String... properties) {
        return new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                .withClassLoader(loader).withUserConfiguration(ErrorHost.class, Holders.class)
                .withBean(ObservationRegistry.class, recorder::registry).withBean(ThreadTenant.class)
                .withPropertyValues("server.port=0", "spring.liquibase.enabled=false")
                .withPropertyValues(properties);
    }

    @Test
    void ac_ext_80_internal_and_loopback_return_and_throw_with_one_observation() throws Exception {
        for (String mode : List.of("internal", "loopback")) {
            for (boolean proxy : List.of(false, true)) {
                CallObservationTest.Recorder recorder = new CallObservationTest.Recorder();
                try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                    server(loader, recorder, "topolith.modules.listed.mode=" + mode,
                            "topolith.modules.listed.expose.enabled=true", "test.listed.proxy=" + proxy)
                            .run(context -> {
                                assertNull(context.getStartupFailure(), mode + " proxy=" + proxy);
                                ListedContract contract = context.getBean(Holder.class).contract();
                                recorder.clear();
                                assertEquals("value:hello", contract.value("hello"));
                                assertEquals(1, recorder.named("topolith.module.call").size());
                                recorder.clear();
                                ListedFailure failure = assertThrows(ListedFailure.class,
                                        () -> contract.value("fail"));
                                assertEquals("declared failure", failure.getMessage());
                                assertEquals(1, recorder.named("topolith.module.call").size());
                                var answer = ProviderErrorsTest.get(context, "/listed/value?input=fail");
                                assertEquals(500, answer.status());
                                assertEquals("listed", answer.module());
                                assertTrue(answer.body().contains(ListedFailure.class.getName()));
                                assertEquals(404, ProviderErrorsTest.get(context,
                                        "/listed/listed/value?input=hello").status());
                                if (mode.equals("loopback")) {
                                    ThreadTenant.set("evil");
                                    recorder.clear();
                                    ModuleUnavailableException rejected = assertThrows(ModuleUnavailableException.class,
                                            () -> contract.value("hello"));
                                    assertTrue(rejected.getMessage().contains("status 403"));
                                    assertEquals(1, recorder.named("topolith.module.call").size());
                                    ThreadTenant.set(null);
                                }
                            });
                }
            }
        }
    }

    @Test
    void ac_ext_80_external_has_the_same_result_exception_and_tenant_rejection() throws Exception {
        CallObservationTest.Recorder recorder = new CallObservationTest.Recorder();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, new CallObservationTest.Recorder(), "topolith.modules.listed.mode=internal",
                    "topolith.modules.listed.expose.enabled=true").run(provider -> {
                        assertNull(provider.getStartupFailure());
                        new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ClientHost.class)
                                .withBean(ObservationRegistry.class, recorder::registry).withBean(ThreadTenant.class)
                                .withPropertyValues("spring.liquibase.enabled=false",
                                        "topolith.modules.listed.mode=external",
                                        "topolith.modules.listed.remote.url=http://127.0.0.1:"
                                                + ProviderErrorsTest.port(provider))
                                .run(client -> {
                                    assertNull(client.getStartupFailure());
                                    ListedContract contract = client.getBean(ListedContract.class);
                                    recorder.clear();
                                    assertEquals("value:hello", contract.value("hello"));
                                    assertEquals(1, recorder.named("topolith.module.call").size());
                                    recorder.clear();
                                    assertEquals("declared failure", assertThrows(ListedFailure.class,
                                            () -> contract.value("fail")).getMessage());
                                    assertEquals(1, recorder.named("topolith.module.call").size());
                                    ThreadTenant.set("evil");
                                    ModuleUnavailableException failure = assertThrows(ModuleUnavailableException.class,
                                            () -> contract.value("hello"));
                                    assertTrue(failure.getMessage().contains("status 403"));
                                });
                    });
        }
    }

    @Test
    void ac_ext_80_entity_and_async_results_match_controller_and_internal_result() throws Exception {
        ContractExceptionsTest.contracts(root, "response", ResponseContract.class);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                            "topolith.modules.listed.mode=none", "topolith.modules.response.mode=internal",
                            "topolith.modules.response.expose.enabled=true")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var entity = get(context, "/response/entity");
                        var reference = get(context, "/response/reference");
                        var internal = context.getBean(ResponseContract.class).entity();
                        assertEquals(201, entity.statusCode());
                        assertEquals(reference.statusCode(), entity.statusCode());
                        assertEquals(reference.headers().firstValue("X-Result"),
                                entity.headers().firstValue("X-Result"));
                        assertEquals(reference.body(), entity.body());
                        assertEquals(internal.getStatusCode().value(), entity.statusCode());
                        assertEquals(internal.getHeaders().getFirst("X-Result"),
                                entity.headers().firstValue("X-Result").orElseThrow());
                        assertEquals(internal.getBody(), entity.body());
                        assertEquals("future body", get(context, "/response/future").body());
                        assertEquals("deferred body", get(context, "/response/deferred").body());
                    });
        }
    }

    @Test
    void ac_ext_80_headers_return_matches_controller_without_a_body() throws Exception {
        ContractExceptionsTest.contracts(root, "response", ResponseContract.class);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                            "topolith.modules.listed.mode=none", "topolith.modules.response.mode=internal",
                            "topolith.modules.response.expose.enabled=true")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var headers = get(context, "/response/headers");
                        var reference = get(context, "/response/headers-reference");
                        assertEquals(200, headers.statusCode());
                        assertEquals(reference.statusCode(), headers.statusCode());
                        assertEquals("b", reference.headers().firstValue("A").orElseThrow());
                        assertEquals(reference.headers().firstValue("A"), headers.headers().firstValue("A"));
                        assertEquals("", reference.body());
                        assertEquals(reference.body(), headers.body());
                    });
        }
    }

    private static HttpResponse<String> get(
            org.springframework.boot.test.context.assertj.AssertableWebApplicationContext context, String path)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + ProviderErrorsTest.port(context) + path)).timeout(Duration.ofSeconds(5)).GET().build();
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void ac_ext_80_registered_http_calls_record_one_observation_across_provider_and_client() throws Exception {
        for (boolean proxy : List.of(false, true)) {
            CallObservationTest.Recorder providerRecorder = new CallObservationTest.Recorder();
            CallObservationTest.Recorder clientRecorder = new CallObservationTest.Recorder();
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                server(loader, providerRecorder, "topolith.modules.listed.mode=internal",
                        "topolith.modules.listed.expose.enabled=true", "test.listed.proxy=" + proxy)
                        .run(provider -> {
                            assertNull(provider.getStartupFailure());
                            new ApplicationContextRunner().withClassLoader(loader)
                                    .withUserConfiguration(ClientHost.class)
                                    .withBean(ObservationRegistry.class, clientRecorder::registry)
                                    .withPropertyValues("spring.liquibase.enabled=false",
                                            "topolith.modules.listed.mode=external",
                                            "topolith.modules.listed.remote.url=http://127.0.0.1:"
                                                    + ProviderErrorsTest.port(provider))
                                    .run(client -> {
                                        assertNull(client.getStartupFailure());
                                        ListedContract contract = client.getBean(ListedContract.class);
                                        for (String input : List.of("hello", "fail")) {
                                            providerRecorder.clear();
                                            clientRecorder.clear();
                                            if (input.equals("fail")) {
                                                assertThrows(ListedFailure.class, () -> contract.value(input));
                                            } else {
                                                assertEquals("value:hello", contract.value(input));
                                            }
                                            int providerCount = providerRecorder.named("topolith.module.call").size();
                                            int clientCount = clientRecorder.named("topolith.module.call").size();
                                            assertEquals(0, providerCount, "provider proxy=" + proxy);
                                            assertEquals(1, clientCount, "client proxy=" + proxy);
                                            assertEquals(1, providerCount + clientCount, "total proxy=" + proxy);
                                        }
                                    });
                        });
            }
        }
    }

    @Test
    void ac_ext_81_inactive_modules_have_no_registered_route_or_registry_entry() throws Exception {
        for (String mode : List.of("none", "external", "internal")) {
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                        .withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                        .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                                "topolith.modules.listed.mode=" + mode,
                                "topolith.modules.listed.remote.url=http://127.0.0.1:1")
                        .run(context -> {
                            assertNull(context.getStartupFailure(), mode);
                            ListedContractRoutes routes = context.getBeanProvider(ListedContractRoutes.class)
                                    .getIfAvailable();
                            assertTrue(routes == null || !routes.contains(
                                    ListedContract.class.getMethod("value", String.class)));
                            assertFalse(context.getBean("requestMappingHandlerMapping",
                                            RequestMappingHandlerMapping.class)
                                    .getHandlerMethods().keySet().stream()
                                    .anyMatch(info -> info.getPatternValues().contains("/listed/value")));
                        });
            }
        }
    }

    @Test
    void ac_ext_80_definition_ownership_selects_module_bean_over_external_client() throws Exception {
        ContractExceptionsTest.contracts(root, "other", OtherContract.class);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                            "topolith.modules.listed.mode=internal", "topolith.modules.listed.expose.enabled=true",
                            "topolith.modules.other.mode=external",
                            "topolith.modules.other.remote.url=http://127.0.0.1:1")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals(2, context.getBeansOfType(ListedContract.class).size());
                        var answer = ProviderErrorsTest.get(context, "/listed/value?input=hello");
                        assertEquals(200, answer.status());
                        assertEquals("value:hello", answer.body());
                    });
        }
    }

    record Payload(String value) {
    }

    interface HintContract {
        @PostExchange("/payload")
        Payload echo(Payload input);
    }

    @Test
    void ac_ext_80_aot_registers_invocable_methods_and_binding_types_only_for_exposed_modules() {
        var resolution = new TopologyResolver.Resolution(null, Map.of(), Map.of(), List.of(), Map.of(),
                Set.of("listed"), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("listed", Set.of(HintContract.class.getName()),
                        "hidden", Set.of(ListedContract.class.getName())));
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton(TopologyResolver.RESOLUTION_BEAN, resolution);
        RuntimeHints hints = new RuntimeHints();
        var generation = new DefaultGenerationContext(new NameGenerator(ClassName.get(HintContract.class)),
                new InMemoryGeneratedFiles(), hints);
        new ListedRouteHints().processAheadOfTime(factory).applyTo(generation, null);
        var contract = hints.reflection().getTypeHint(HintContract.class);
        assertTrue(contract.methods().anyMatch(method -> method.getName().equals("echo")
                && method.getMode() == ExecutableMode.INVOKE));
        assertTrue(hints.reflection().getTypeHint(Payload.class) != null);
        assertNull(hints.reflection().getTypeHint(ListedContract.class));
    }
}
