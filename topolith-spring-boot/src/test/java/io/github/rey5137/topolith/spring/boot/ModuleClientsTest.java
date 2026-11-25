package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpServer;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.Host;
import io.github.rey5137.topolith.spring.boot.fixture.local.api.LocalContract;
import io.github.rey5137.topolith.spring.boot.fixture.remote.api.RemoteContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleClientsTest {
    @TempDir
    Path temp;

    @Configuration(proxyBeanMethods = false)
    static class Customization {
        @Bean
        RestClientHttpServiceGroupConfigurer customClient() {
            return ModuleClients.configure("remote", builder -> builder.defaultHeader("X-Custom", "yes"));
        }
    }

    private void descriptor(String id, Class<?> contract) throws IOException {
        String pkg = Fixtures.BASE + "." + id;
        Path api = temp.resolve("META-INF/topolith/apis/" + id + ".properties");
        Path module = temp.resolve("META-INF/topolith/modules/" + id + ".properties");
        Files.createDirectories(api.getParent());
        Files.createDirectories(module.getParent());
        try (Writer out = Files.newBufferedWriter(api)) {
            Descriptors.write(ApiDescriptor.builder(id).apiPackages(List.of(pkg + ".api")).apiVersion("1.0.0")
                    .provides(List.of(contract.getName())).build(), out);
        }
        try (Writer out = Files.newBufferedWriter(module)) {
            Descriptors.write(ModuleDescriptor.builder(id).packageName(pkg).apiPackages(List.of(pkg + ".api"))
                    .apiVersion("1.0.0").provides(List.of(contract.getName())).build(), out);
        }
    }

    @Test
    void ac_ext_15_only_named_group_receives_customization() throws Exception {
        descriptor("remote", RemoteContract.class);
        descriptor("local", LocalContract.class);
        var headers = new ConcurrentHashMap<String, String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            headers.put(exchange.getRequestURI().getPath(),
                    String.valueOf(exchange.getRequestHeaders().getFirst("X-Custom")));
            byte[] body = "pong".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(temp)) {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            new ApplicationContextRunner().withClassLoader(loader)
                    .withUserConfiguration(Host.class, Customization.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.remote.mode=external",
                            "topolith.modules.local.mode=external", "topolith.modules.remote.remote.url=" + url,
                            "topolith.modules.local.remote.url=" + url).run(context -> {
                                assertNull(context.getStartupFailure());
                                assertEquals("pong", context.getBean(RemoteContract.class).ping());
                                assertEquals("pong", context.getBean(LocalContract.class).ping());
                                assertEquals("yes", headers.get("/remote/ping"));
                                assertEquals("null", headers.get("/local/ping"));
                            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ac_ext_15_rejects_unknown_and_non_external_module() throws Exception {
        for (String mode : List.of("unknown", "none", "internal")) {
            Path root = Files.createDirectory(temp.resolve(mode));
            if (!mode.equals("unknown")) {
                descriptor("remote", RemoteContract.class);
                root = temp;
            }
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                var runner = new ApplicationContextRunner().withClassLoader(loader)
                        .withUserConfiguration(Host.class, Customization.class)
                        .withPropertyValues("spring.liquibase.enabled=false").withInitializer(context ->
                                context.addBeanFactoryPostProcessor(
                                        new org.springframework.boot.LazyInitializationBeanFactoryPostProcessor()));
                if (!mode.equals("unknown")) {
                    runner = runner.withPropertyValues("topolith.modules.remote.mode=" + mode);
                }
                runner.run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    StringBuilder causes = new StringBuilder();
                    TopolithException diagnostic = null;
                    while (failure != null) {
                        causes.append(failure.getMessage());
                        if (failure instanceof TopolithException found) {
                            diagnostic = found;
                        }
                        failure = failure.getCause();
                    }
                    assertNotNull(diagnostic);
                    assertEquals(List.of(TopolithCode.TL0003), diagnostic.diagnostics().stream()
                            .map(d -> d.code()).toList());
                    assertTrue(causes.toString().contains("TL0003"), causes.toString());
                    assertTrue(causes.toString().contains("topolith.modules.remote.mode"), causes.toString());
                });
            }
        }
    }
    @Test
    void ac_ext_15_topolith_client_settings_cannot_be_replaced() throws Exception {
        descriptor("remote", RemoteContract.class);
        for (String setting : List.of("baseUrl", "requestFactory", "defaultUriVariables", "messageConverters",
                "configureMessageConverters")) {
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(temp)) {
                new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                        .withBean("custom", RestClientHttpServiceGroupConfigurer.class, () ->
                                ModuleClients.configure("remote", builder -> {
                                    if (setting.equals("baseUrl")) {
                                        builder.baseUrl("http://other");
                                    } else if (setting.equals("defaultUriVariables")) {
                                        builder.defaultUriVariables(java.util.Map.of("a", "b"));
                                    } else if (setting.equals("messageConverters")) {
                                        builder.messageConverters(List.of(new org.springframework.http.converter
                                                .StringHttpMessageConverter()));
                                    } else if (setting.equals("configureMessageConverters")) {
                                        builder.configureMessageConverters(converters -> { });
                                    } else {
                                        builder.requestFactory(new org.springframework.http.client
                                                .SimpleClientHttpRequestFactory());
                                    }
                                }))
                        .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.remote.mode=external",
                                "topolith.modules.remote.remote.url=http://localhost:1")
                        .run(context -> {
                            Throwable failure = context.getStartupFailure();
                            assertNotNull(failure);
                            StringBuilder messages = new StringBuilder();
                            while (failure != null) {
                                messages.append(failure.getMessage());
                                failure = failure.getCause();
                            }
                            assertTrue(messages.toString().contains("Topolith owns client setting " + setting),
                                    messages.toString());
                        });
            }
        }
    }


    @Configuration(proxyBeanMethods = false)
    @org.springframework.web.service.registry.ImportHttpServices(group = "remote",
            types = io.github.rey5137.topolith.spring.boot.errorfixture.host.HostClient.class)
    static class CollidingGroup {
    }

    @Test
    void ac_ext_31_host_group_named_like_an_external_module_fails_with_tl0003() throws Exception {
        descriptor("remote", RemoteContract.class);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(temp)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class, CollidingGroup.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.remote.mode=external",
                            "topolith.modules.remote.remote.url=http://localhost:1").run(context -> {
                                Throwable failure = context.getStartupFailure();
                                TopolithException diagnostic = null;
                                while (failure != null) {
                                    if (failure instanceof TopolithException found) {
                                        diagnostic = found;
                                    }
                                    failure = failure.getCause();
                                }
                                assertNotNull(diagnostic);
                                assertEquals(TopolithCode.TL0003, diagnostic.diagnostics().get(0).code());
                                assertTrue(diagnostic.getMessage().contains("'remote'"), diagnostic.getMessage());
                            });
        }
    }
}
