package io.github.rey5137.topolith.spring.boot;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.Host;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class TopolithBoundaryCheckTest {
    @TempDir
    Path root;

    private Path fixture(String reference, String baseline, boolean jar) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path alpha = root.resolve("src/fixture/alpha/Caller.java");
        Path hidden = root.resolve("src/fixture/beta/Hidden.java");
        Path api = root.resolve("src/fixture/beta/api/Contract.java");
        Files.createDirectories(alpha.getParent());
        Files.createDirectories(hidden.getParent());
        Files.createDirectories(api.getParent());
        Files.writeString(alpha, "package fixture.alpha; public class Caller { public " + reference + " field; }");
        Files.writeString(hidden, "package fixture.beta; public class Hidden {} ");
        Files.writeString(api, "package fixture.beta.api; public class Contract {} ");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                alpha.toString(), hidden.toString(), api.toString()));
        Fixtures.module(classes, "alpha", "fixture.alpha", List.of("fixture.alpha.api"));
        Fixtures.module(classes, "beta", "fixture.beta", List.of("fixture.beta.api"));
        if (baseline != null) {
            Fixtures.resource(classes, "META-INF/topolith/baseline/alpha.txt", baseline);
        }
        if (!jar) {
            return classes;
        }
        Path archive = root.resolve("fixture.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(archive)); var paths = Files.walk(classes)) {
            for (Path path : paths.filter(p -> !p.equals(classes)).sorted().toList()) {
                String name = classes.relativize(path).toString().replace('\\', '/');
                out.putNextEntry(new JarEntry(name + (Files.isDirectory(path) ? "/" : "")));
                if (!Files.isDirectory(path)) {
                    Files.copy(path, out);
                }
                out.closeEntry();
            }
        }
        return archive;
    }

    private void run(Path fixture, TopolithCode expected, String... messages) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[] {fixture.toUri().toURL()},
                getClass().getClassLoader())) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("topolith.modules.alpha.mode=internal", "topolith.modules.beta.mode=none",
                            "spring.liquibase.enabled=false")
                    .run(context -> {
                        Throwable error = context.getStartupFailure();
                        if (expected == null) {
                            assertNull(error);
                            return;
                        }
                        while (error != null && !(error instanceof TopolithException)) {
                            error = error.getCause();
                        }
                        assertNotNull(error);
                        TopolithException failure = (TopolithException) error;
                        assertEquals(expected, failure.code());
                        for (String message : messages) {
                            assertTrue(failure.getMessage().contains(message), failure.getMessage());
                        }
                    });
        }
    }

    @Test
    void ac_arch_11_non_internal_module_is_not_scanned() throws Exception {
        Path fixture = fixture("fixture.beta.Hidden", null, false);
        try (var loader = new URLClassLoader(new java.net.URL[] {fixture.toUri().toURL()},
                getClass().getClassLoader())) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("topolith.modules.alpha.mode=none", "topolith.modules.beta.mode=none",
                            "spring.liquibase.enabled=false")
                    .run(context -> assertNull(context.getStartupFailure()));
        }
    }

    @Test
    void ac_arch_11_non_api_reference_fails() throws Exception {
        run(fixture("fixture.beta.Hidden", null, false), TopolithCode.TL0012,
                "fixture.alpha.Caller", "fixture.beta.Hidden");
    }

    @Test
    void ac_arch_11_generic_signature_reference_fails() throws Exception {
        run(fixture("java.util.List<fixture.beta.Hidden>", null, false), TopolithCode.TL0012,
                "fixture.alpha.Caller", "fixture.beta.Hidden");
    }

    @Test
    void ac_arch_11_different_frozen_pair_does_not_suppress() throws Exception {
        run(fixture("fixture.beta.Hidden", "fixture.alpha.Other -> fixture.beta.Hidden\n", false),
                TopolithCode.TL0012, "fixture.alpha.Caller", "fixture.beta.Hidden");
    }

    @Test
    void ac_arch_17_annotation_only_reference_fails() throws Exception {
        Path classes = fixture("java.lang.Object", null, false);
        Path annotation = root.resolve("src/fixture/beta/Marker.java");
        Path caller = root.resolve("src/fixture/alpha/Caller.java");
        Files.writeString(annotation, "package fixture.beta; public @interface Marker {} ");
        Files.writeString(caller, "package fixture.alpha; @fixture.beta.Marker public class Caller {} ");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                caller.toString(), annotation.toString()));
        run(classes, TopolithCode.TL0012, "fixture.alpha.Caller", "fixture.beta.Marker");
    }

    @Test
    void ac_arch_33_annotation_class_value_reference_fails() throws Exception {
        Path classes = fixture("java.lang.Object", null, false);
        Path annotation = root.resolve("src/fixture/alpha/Marker.java");
        Path caller = root.resolve("src/fixture/alpha/Caller.java");
        Files.writeString(annotation, "package fixture.alpha; public @interface Marker { Class<?> value(); } ");
        Files.writeString(caller, "package fixture.alpha; @Marker(fixture.beta.Hidden.class) public class Caller {} ");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", classes.toString(),
                "-d", classes.toString(), caller.toString(), annotation.toString()));
        run(classes, TopolithCode.TL0012, "fixture.alpha.Caller", "fixture.beta.Hidden");
    }

    @Test
    void ac_arch_12_packaged_baseline_freezes_pair() throws Exception {
        run(fixture("fixture.beta.Hidden", "fixture.alpha.Caller -> fixture.beta.Hidden\n", true), null);
    }

    @Test
    void ac_arch_13_api_reference_passes() throws Exception {
        run(fixture("fixture.beta.api.Contract", null, false), null);
    }

    @Test
    void ac_arch_15_baseline_ignores_comments_and_blank_lines() throws Exception {
        run(fixture("fixture.beta.Hidden", "# frozen\n\nfixture.alpha.Caller -> fixture.beta.Hidden\n", false), null);
    }

    @Test
    void ac_arch_16_malformed_baseline_names_resource_and_line() throws Exception {
        run(fixture("fixture.beta.Hidden", "not a pair\n", true), TopolithCode.TL0030, "alpha.txt", "not a pair");
    }
}
