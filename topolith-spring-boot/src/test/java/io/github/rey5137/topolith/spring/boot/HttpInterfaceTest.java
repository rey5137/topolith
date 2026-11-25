package io.github.rey5137.topolith.spring.boot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.separate.PlainHttp;
import io.github.rey5137.topolith.spring.boot.errorfixture.separate.api.PlainContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.separate.api.PlainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpInterfaceTest {
    private static final String PROPERTY = "topolith.modules.separate.remote.http-interface."
            + PlainContract.class.getName();
    @TempDir
    Path root;

    interface Unannotated extends PlainContract {
    }

    @org.springframework.web.service.annotation.HttpExchange
    interface Unrelated {
    }

    interface MethodOnly extends PlainContract {
        @Override
        @org.springframework.web.service.annotation.GetExchange("/find")
        String find(@org.springframework.web.bind.annotation.RequestParam("value") String value);
    }

    @BeforeEach
    void setup() throws Exception {
        ContractExceptionsTest.contracts(root, "separate", PlainContract.class);
    }

    @Test
    void ac_ext_03_and_ac_ext_15_customized_interface_translates_errors() throws Exception {
        try (var bytes = HttpInterfaceTest.class.getClassLoader().getResourceAsStream(
                "io/github/rey5137/topolith/spring/boot/errorfixture/separate/api/PlainContract.class")) {
            assertNotNull(bytes);
            assertFalse(new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1)
                    .contains("org/springframework/web"));
        }
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                            "topolith.modules.separate.mode=internal", "topolith.modules.separate.expose.enabled=true")
                    .run(provider -> {
                        assertNull(provider.getStartupFailure());
                        String url = "http://127.0.0.1:" + ProviderErrorsTest.port(provider);
                        AtomicReference<String> path = new AtomicReference<>();
                        AtomicReference<String> header = new AtomicReference<>();
                        new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                                .withBean("custom", RestClientHttpServiceGroupConfigurer.class, () ->
                                        ModuleClients.configure("separate", builder -> builder
                                                .defaultHeader("X-Custom", "yes")
                                                .requestInterceptor((request, body, execution) -> {
                                                    path.set(request.getURI().getPath());
                                                    header.set(request.getHeaders().getFirst("X-Custom"));
                                                    return execution.execute(request, body);
                                                })))
                                .withPropertyValues("spring.liquibase.enabled=false",
                                        "topolith.modules.separate.mode=external",
                                        "topolith.modules.separate.remote.url=" + url,
                                        PROPERTY + "=" + PlainHttp.class.getName())
                                .run(caller -> {
                                    assertNull(caller.getStartupFailure());
                                    PlainContract client = caller.getBean(PlainContract.class);
                                    assertEquals("found ok", client.find("ok"));
                                    assertEquals("failed", assertThrows(PlainException.class,
                                            () -> client.find("fail")).getMessage());
                                    assertEquals("/separate/find", path.get());
                                    assertEquals("yes", header.get());
                                });
                    });
        }
    }

    @Test
    void ac_ext_03_method_annotation_is_sufficient() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.separate.mode=external",
                            "topolith.modules.separate.remote.url=http://localhost:1",
                            PROPERTY + "=" + MethodOnly.class.getName()).run(context -> {
                                assertNull(context.getStartupFailure());
                                assertNotNull(context.getBean(PlainContract.class));
                            });
        }
    }

    @Test
    void ac_ext_03_invalid_interfaces_fail_tl0026() throws Exception {
        for (String value : List.of("missing.NoSuchInterface", String.class.getName(), Runnable.class.getName(),
                Unrelated.class.getName(), Unannotated.class.getName())) {
            check("external", PROPERTY, value, "TL0026", PlainContract.class.getName(), value);
        }
    }

    @Test
    void ac_ext_03_invalid_mapping_scope_fails_tl0003() throws Exception {
        for (String mode : List.of("none", "internal")) {
            check(mode, PROPERTY, PlainHttp.class.getName(), "TL0003", "separate", "mode " + mode, "not external");
        }
        check("external", "topolith.modules.unknown.remote.http-interface." + PlainContract.class.getName(),
                PlainHttp.class.getName(), "TL0003", "unknown, not external");
        check("external", "topolith.modules.separate.remote.http-interface.missing.Contract", PlainHttp.class.getName(),
                "TL0003", "missing.Contract", "not in provides");
        check("external", "topolith.modules.separate.remote.http-interface.bad-key", PlainHttp.class.getName(),
                "TL0003", "malformed contract key");
        check("external", PROPERTY.toLowerCase(Locale.ROOT), PlainHttp.class.getName(),
                "TL0003", "not in provides", "environment variables");
        check("external", "topolith.modules.separate.remote.http-interface", PlainHttp.class.getName(),
                "TL0003", "remote.http-interface");
        check("external", "topolith.modules.separate.remote", "foo", "TL0003", "remote");
    }

    private void check(String mode, String property, String value, String... expected) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.separate.mode=" + mode,
                            "topolith.modules.separate.remote.url=http://localhost:1", property + "=" + value)
                    .run(context -> {
                        Throwable failure = context.getStartupFailure();
                        assertNotNull(failure);
                        StringBuilder messages = new StringBuilder();
                        TopolithException diagnostic = null;
                        while (failure != null) {
                            messages.append(failure.getMessage());
                            if (failure instanceof TopolithException found) {
                                diagnostic = found;
                            }
                            failure = failure.getCause();
                        }
                        assertNotNull(diagnostic);
                        assertTrue(diagnostic.diagnostics().stream()
                                .anyMatch(d -> d.code() == TopolithCode.valueOf(expected[0])));
                        for (String part : expected) {
                            assertTrue(messages.toString().contains(part), messages.toString());
                        }
                    });
        }
    }
}
