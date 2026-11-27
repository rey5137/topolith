package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import io.github.rey5137.topolith.contract.Idempotent;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.idem.api.DefaultContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.idem.api.OverloadContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.idem.api.ReadContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The startup check of the descriptors' {@code idempotent} sets against {@code @Idempotent} (AC-DESC-13). */
class IdempotencyStartupTest {
    private static final String ID = "idem";
    private static final String MODULE = Fixtures.ERRORS + ".idem";
    private static final String READ = ReadContract.class.getName();
    private static final Set<String> READ_IDEMPOTENT = Set.of(READ + "#count", READ + "#get");
    private static final String MAPPING = "topolith.modules.idem.remote.http-interface." + READ;

    @TempDir
    Path root;

    @HttpExchange
    @Idempotent
    interface TypeMarked extends ReadContract {
    }

    interface MethodMarked extends ReadContract {
        @Override
        @Idempotent
        @GetExchange("/get")
        String get(@RequestParam("id") String id);
    }

    interface Plain extends ReadContract {
    }

    @Idempotent
    interface MarkedMiddle extends ReadContract {
    }

    @HttpExchange
    interface ViaMiddle extends MarkedMiddle {
    }

    private void descriptors(Set<String> module, Set<String> api, Class<?>... contracts) throws IOException {
        List<String> names = Arrays.stream(contracts).map(Class::getName).toList();
        write("apis", out -> Descriptors.write(ApiDescriptor.builder(ID).apiPackages(List.of(MODULE + ".api"))
                .apiVersion("1.0.0").provides(names).idempotent(api).build(), out));
        write("modules", out -> Descriptors.write(ModuleDescriptor.builder(ID).packageName(MODULE)
                .apiPackages(List.of(MODULE + ".api")).apiVersion("1.0.0").provides(names).idempotent(module)
                .build(), out));
    }

    private interface Body {
        void write(Writer out) throws IOException;
    }

    private void write(String kind, Body body) throws IOException {
        Path file = root.resolve("META-INF/topolith/" + kind + "/" + ID + ".properties");
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            body.write(out);
        }
    }

    /** The startup failure's TL0040 messages for {@code mode}; empty when the context started. */
    private List<String> tl0040(String mode, String... properties) throws IOException {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            List<String> found = new java.util.ArrayList<>();
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.idem.mode=" + mode)
                    .withPropertyValues(mode.equals("external")
                            ? new String[] {"topolith.modules.idem.remote.url=http://localhost:1"} : new String[0])
                    .withPropertyValues(properties)
                    .run(context -> {
                        for (Throwable failure = context.getStartupFailure(); failure != null;
                                failure = failure.getCause()) {
                            if (failure instanceof TopolithException topolith) {
                                topolith.diagnostics().stream().filter(d -> d.code() == TopolithCode.TL0040)
                                        .forEach(d -> found.add(d.toString()));
                                break;
                            }
                        }
                    });
            return found;
        }
    }

    /** Asserts that the context starts: a servlet server for a loopback module, a plain context otherwise. */
    private void assertStarts(String mode, String... properties) throws IOException {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            if (mode.equals("loopback")) {
                new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                        .withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                        .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                                "topolith.modules.idem.mode=loopback")
                        .withPropertyValues(properties)
                        .run(context -> assertNull(context.getStartupFailure(), mode));
            } else {
                new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                        .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.idem.mode=" + mode,
                                "topolith.modules.idem.remote.url=http://localhost:1")
                        .withPropertyValues(properties)
                        .run(context -> assertNull(context.getStartupFailure(), mode));
            }
        }
    }

    private void assertFails(List<String> messages, String... fragments) {
        assertTrue(!messages.isEmpty(), "expected a TL0040 failure");
        String all = String.join("\n", messages);
        for (String fragment : fragments) {
            assertTrue(all.contains(fragment), "expected '" + fragment + "' in: " + all);
        }
    }

    @Test
    void ac_desc_13_matching_descriptors_start_internal_external_and_loopback_modules() throws Exception {
        descriptors(READ_IDEMPOTENT, READ_IDEMPOTENT, ReadContract.class);
        for (String mode : List.of("internal", "external", "loopback")) {
            assertStarts(mode);
        }
    }

    @Test
    void ac_desc_13_an_external_modules_idempotent_set_is_its_api_descriptors_not_the_union() throws Exception {
        descriptors(Set.of(READ + "#count", READ + "#get", READ + "#save"), READ_IDEMPOTENT, ReadContract.class);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.idem.mode=external",
                            "topolith.modules.idem.remote.url=http://localhost:1")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        TopologyResolver.Resolution resolution = context.getBean(TopologyResolver.RESOLUTION_BEAN,
                                TopologyResolver.Resolution.class);
                        assertEquals(READ_IDEMPOTENT, resolution.idempotent().get("idem"));
                    });
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.idem.mode=none")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertNull(context.getBean(TopologyResolver.RESOLUTION_BEAN, TopologyResolver.Resolution.class)
                                .idempotent().get("idem"));
                    });
        }
    }

    @Test
    void ac_desc_13_a_descriptor_that_differs_from_the_annotations_fails_tl0040() throws Exception {
        descriptors(Set.of(READ + "#get"), Set.of(READ + "#get"), ReadContract.class);
        for (String mode : List.of("internal", "loopback", "external")) {
            assertFails(tl0040(mode), mode + " module 'idem' contract " + READ + " is declared idempotent for ["
                    + READ + "#get]", "annotations give [" + READ + "#count, " + READ + "#get]");
        }
        Set<String> extra = Set.of(READ + "#count", READ + "#get", READ + "#save");
        descriptors(extra, extra, ReadContract.class);
        assertFails(tl0040("internal"), "contract " + READ, READ + "#save");
    }

    @Test
    void ac_desc_13_module_and_api_descriptors_that_differ_fail_internal_and_loopback() throws Exception {
        descriptors(READ_IDEMPOTENT, Set.of(READ + "#get"), ReadContract.class);
        for (String mode : List.of("internal", "loopback")) {
            List<String> messages = tl0040(mode);
            assertEquals(1, messages.size(), messages.toString());
            assertFails(messages, mode + " module 'idem' declares idempotent [" + READ + "#count, " + READ + "#get]",
                    "modules/idem.properties", "but [" + READ + "#get] in", "apis/idem.properties");
        }
        // External reads only the API descriptor, so the host's module descriptor is not its effective one.
        descriptors(READ_IDEMPOTENT, Set.of(READ + "#get"), ReadContract.class);
        assertFails(tl0040("external"), "external module 'idem' contract " + READ);
        descriptors(Set.of(READ + "#get"), READ_IDEMPOTENT, ReadContract.class);
        assertStarts("external");
    }

    @Test
    void ac_desc_13_a_default_method_or_disagreeing_overloads_fail_tl0040() throws Exception {
        descriptors(Set.of(), Set.of(), DefaultContract.class);
        assertFails(tl0040("internal"), "contract " + DefaultContract.class.getName()
                + " has @Idempotent on default method first");
        Set<String> find = Set.of(OverloadContract.class.getName() + "#find");
        descriptors(find, find, OverloadContract.class);
        assertFails(tl0040("external"), "contract " + OverloadContract.class.getName()
                + " has overloads of find that disagree on @Idempotent");
    }

    @Test
    void ac_desc_13_idempotent_on_a_separate_http_interface_fails_tl0040() throws Exception {
        descriptors(READ_IDEMPOTENT, READ_IDEMPOTENT, ReadContract.class);
        for (Class<?> http : List.of(TypeMarked.class, MethodMarked.class)) {
            for (String mode : List.of("external", "loopback")) {
                List<String> messages = tl0040(mode, MAPPING + "=" + http.getName());
                assertEquals(1, messages.size(), messages.toString());
                assertFails(messages, mode + " module 'idem' maps contract " + READ + " to HTTP interface "
                        + http.getName() + ", which carries @Idempotent");
            }
        }
        for (String mode : List.of("external", "loopback")) {
            assertStarts(mode, MAPPING + "=" + Plain.class.getName());
            assertStarts(mode, MAPPING + "=" + READ);
        }
        List<String> middle = tl0040("external", MAPPING + "=" + ViaMiddle.class.getName());
        assertEquals(1, middle.size(), middle.toString());
        assertFails(middle, "maps contract " + READ + " to HTTP interface " + ViaMiddle.class.getName()
                + ", which carries @Idempotent on [" + MarkedMiddle.class.getName() + "]");
    }

    /** A contract whose method types are missing is skipped, not a raw error that would hide TL0026 (D-41). */
    @Test
    void ac_desc_13_a_contract_with_unloadable_method_types_is_skipped() throws Exception {
        Path classes = Files.createDirectories(root.resolve("compiled"));
        Path source = Files.createDirectories(root.resolve("src/com/acme/broken/api"));
        Files.writeString(source.resolve("Missing.java"), "package com.acme.broken.api; public class Missing {}\n");
        Files.writeString(source.resolve("Broken.java"), "package com.acme.broken.api; public interface Broken {"
                + " @" + Idempotent.class.getName() + " Missing find(); }\n");
        Files.writeString(source.resolve("Http.java"),
                "package com.acme.broken.api; public interface Http extends Broken { Missing other(); }\n");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d",
                classes.toString(), "-classpath",
                Path.of(Idempotent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                source.resolve("Missing.java").toString(), source.resolve("Broken.java").toString(),
                source.resolve("Http.java").toString()));
        Files.delete(classes.resolve("com/acme/broken/api/Missing.class"));
        String broken = "com.acme.broken.api.Broken";
        ModuleDescriptor module = ModuleDescriptor.builder("broken").packageName("com.acme.broken")
                .apiPackages(List.of("com.acme.broken.api")).apiVersion("1").provides(List.of(broken)).build();
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            assertTrue(loader.loadClass(broken).isInterface());
            assertThrows(NoClassDefFoundError.class, () -> loader.loadClass(broken).getDeclaredMethods());
            for (String mode : List.of("internal", "external")) {
                io.github.rey5137.topolith.core.Topology topology = io.github.rey5137.topolith.core.Topology
                        .resolve(List.of(module), List.of(module.api()), java.util.Map.of("broken", mode), null);
                List<io.github.rey5137.topolith.core.Diagnostic> problems = new java.util.ArrayList<>();
                IdempotencyCheck.check(topology, List.of(module), List.of(module.api()),
                        java.util.Map.of("broken", java.util.Map.of(broken, "com.acme.broken.api.Http")), loader,
                        problems);
                assertEquals(List.of(), problems, mode);
            }
        }
    }

    @Test
    void ac_desc_13_a_none_module_is_not_checked() throws Exception {
        descriptors(Set.of(READ + "#save"), Set.of(), ReadContract.class, DefaultContract.class,
                OverloadContract.class);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.idem.mode=none")
                    .run(context -> assertNull(context.getStartupFailure()));
            assertEquals(List.of(), loader.requested(MODULE + "."));
        }
    }
}
