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
        run(new Path[] {fixture}, expected, messages);
    }

    private void run(Path[] fixtures, TopolithCode expected, String... messages) throws Exception {
        var urls = new java.net.URL[fixtures.length];
        for (int i = 0; i < fixtures.length; i++) {
            urls[i] = fixtures[i].toUri().toURL();
        }
        try (var loader = new URLClassLoader(urls, getClass().getClassLoader())) {
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

    /** A second class-path root without a descriptor, holding {@code fixture.alpha.CallerTest}. */
    private Path extraRoot(Path classes, String baseline) throws Exception {
        Path extra = Files.createDirectories(root.resolve("extra"));
        Path source = root.resolve("src-extra/fixture/alpha/CallerTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package fixture.alpha; public class CallerTest { public fixture.beta.Hidden f; }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", classes.toString(), "-d",
                extra.toString(), source.toString()));
        if (baseline != null) {
            Fixtures.resource(extra, "META-INF/topolith/baseline/alpha.txt", baseline);
        }
        return extra;
    }

    @Test
    void ac_arch_11_a_violating_class_in_a_root_without_the_descriptor_is_not_scanned() throws Exception {
        Path classes = fixture("fixture.beta.api.Contract", null, false);
        run(new Path[] {classes, extraRoot(classes, null)}, null);
    }

    @Test
    void ac_arch_11_the_same_class_in_the_descriptor_root_fails() throws Exception {
        Path classes = fixture("fixture.beta.api.Contract", null, false);
        Path extra = extraRoot(classes, null);
        Files.createDirectories(classes.resolve("fixture/alpha"));
        Files.move(extra.resolve("fixture/alpha/CallerTest.class"), classes.resolve("fixture/alpha/CallerTest.class"));
        run(new Path[] {classes, extra}, TopolithCode.TL0012, "fixture.alpha.CallerTest", "fixture.beta.Hidden");
    }

    private static final String PAIR = "fixture.alpha.Caller -> fixture.beta.Hidden\n";

    @Test
    void ac_arch_15_a_baseline_in_a_directory_root_without_a_descriptor_waives_the_pair() throws Exception {
        Path classes = fixture("fixture.beta.Hidden", null, false);
        Path resources = Files.createDirectories(root.resolve("resources"));
        Fixtures.resource(resources, "META-INF/topolith/baseline/alpha.txt", PAIR);
        run(new Path[] {classes, resources}, null);
    }

    @Test
    void ac_arch_15_a_baseline_in_a_jar_without_a_descriptor_fails_tl0030() throws Exception {
        Path classes = fixture("fixture.beta.Hidden", null, false);
        Path archive = root.resolve("resources.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new JarEntry("META-INF/topolith/baseline/alpha.txt"));
            out.write(PAIR.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        run(new Path[] {classes, archive}, TopolithCode.TL0030, "alpha.txt", "resources.jar");
    }

    @Test
    void ac_arch_15_baselines_in_the_descriptor_root_and_a_resource_directory_fail_tl0030_naming_both()
            throws Exception {
        Path classes = fixture("fixture.beta.Hidden", PAIR, false);
        Path resources = Files.createDirectories(root.resolve("resources"));
        Fixtures.resource(resources, "META-INF/topolith/baseline/alpha.txt", PAIR);
        run(new Path[] {classes, resources}, TopolithCode.TL0030, "exists twice", classes.toUri().toURL().toString(),
                resources.toUri().toURL().toString());
    }

    @Test
    void ac_arch_15_a_baseline_in_another_modules_descriptor_root_fails_tl0030() throws Exception {
        Path classes = fixture("fixture.beta.Hidden", null, false);
        Path other = Files.createDirectories(root.resolve("other"));
        Files.delete(classes.resolve("META-INF/topolith/modules/beta.properties"));
        Fixtures.module(other, "beta", "fixture.beta", List.of("fixture.beta.api"));
        Fixtures.resource(other, "META-INF/topolith/baseline/alpha.txt", PAIR);
        run(new Path[] {classes, other}, TopolithCode.TL0030, "alpha.txt", "descriptor root");
    }

    @Test
    void ac_arch_16_the_fix_it_names_the_baseline_tooling() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[] {fixture("fixture.beta.Hidden", "bad\n", false)
                .toUri().toURL()}, getClass().getClassLoader())) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("topolith.modules.alpha.mode=internal", "topolith.modules.beta.mode=none",
                            "spring.liquibase.enabled=false")
                    .run(context -> {
                        Throwable error = context.getStartupFailure();
                        while (!(error instanceof TopolithException)) {
                            error = error.getCause();
                        }
                        assertTrue(error.toString().contains("TopolithBaseline"), error.toString());
                    });
        }
    }

    @Test
    void ac_arch_11_root_prefix_matches_nested_jar_urls() {
        String descriptor = "jar:nested:/app.jar/!BOOT-INF/classes/!/META-INF/topolith/modules/a.properties";
        String root = DescriptorDiscovery.root(descriptor, DescriptorDiscovery.MODULES);
        assertEquals("jar:nested:/app.jar/!BOOT-INF/classes/!/", root);
        var roots = java.util.Set.of(root);
        assertTrue(TopolithBoundaryCheck.inRoot("jar:nested:/app.jar/!BOOT-INF/classes/!/fixture/a/X.class", roots));
        assertFalse(TopolithBoundaryCheck.inRoot("jar:nested:/app.jar/!BOOT-INF/lib/other.jar!/fixture/a/Y.class",
                roots));
    }

    @Test
    void ac_arch_11_descriptor_roots_of_an_unknown_module_are_empty() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[0], getClass().getClassLoader())) {
            assertTrue(TopolithBoundaryCheck.descriptorRoots(loader, "missing").isEmpty());
        }
    }
}
