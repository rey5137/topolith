package io.github.rey5137.topolith.spring.cloud;

import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.spring.cloud.fixture.host.PayCaller;
import io.github.rey5137.topolith.spring.cloud.fixture.host.PayHost;
import io.github.rey5137.topolith.spring.cloud.fixture.pay.api.PayContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** AC-MODE-53: Spring Cloud LoadBalancer on the classpath does not intercept a loopback group. */
class LoopbackLoadBalancerTest {
    private static final String API = PayContract.class.getPackageName();
    private static final String PACKAGE = "io.github.rey5137.topolith.spring.cloud.fixture.pay";

    @TempDir
    Path root;

    private void descriptors() throws IOException {
        String contract = PayContract.class.getName();
        write("apis", out -> Descriptors.write(ApiDescriptor.builder("pay").apiPackages(List.of(API))
                .apiVersion("1.0.0").provides(List.of(contract)).build(), out));
        write("modules", out -> Descriptors.write(ModuleDescriptor.builder("pay").packageName(PACKAGE)
                .apiPackages(List.of(API)).apiVersion("1.0.0").provides(List.of(contract)).build(), out));
    }

    private void write(String kind, Body body) throws IOException {
        Path file = root.resolve("META-INF/topolith/" + kind + "/pay.properties");
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            body.write(out);
        }
    }

    private interface Body {
        void write(Writer out) throws IOException;
    }

    @Test
    void ac_mode_53_a_loopback_call_reaches_the_host_server_with_load_balancer_on_the_classpath() throws Exception {
        descriptors();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()},
                LoopbackLoadBalancerTest.class.getClassLoader())) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(PayHost.class)
                    .withPropertyValues("server.port=0", "topolith.modules.pay.mode=loopback").run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals("pong", context.getBean(PayCaller.class).pay.ping());
                    });
        }
    }
}
