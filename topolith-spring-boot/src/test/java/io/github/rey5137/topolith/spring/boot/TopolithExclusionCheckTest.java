package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.Fixtures.RecordingLoader;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.ExcludingHost;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.Host;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.SliceHost;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.autoconfigure.AutoConfigurationImportEvent;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static io.github.rey5137.topolith.spring.boot.Fixtures.BASE;
import static io.github.rey5137.topolith.spring.boot.Fixtures.USER;
import static io.github.rey5137.topolith.spring.boot.Fixtures.USER_API;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** M8.2 acceptance tests: excluding a Topolith auto-configuration while a module is internal (D-111). */
class TopolithExclusionCheckTest {
    private static final String TOPOLITH = TopolithAutoConfiguration.class.getName();
    private static final String RESILIENCE = "io.github.rey5137.topolith.spring.cloud."
            + "TopolithResilienceAutoConfiguration";
    private static final String JOB = BASE + ".job";

    @TempDir
    Path temp;
    Path jobRoot;
    Path userRoot;
    Path hostRoot;

    @BeforeEach
    void roots() throws IOException {
        jobRoot = Fixtures.dir(temp, "job");
        Fixtures.module(jobRoot, "job", JOB, List.of());
        userRoot = Fixtures.dir(temp, "user");
        Fixtures.module(userRoot, "user", USER, List.of(USER_API));
        hostRoot = Fixtures.dir(temp, "host");
    }

    private interface Check {
        void check(AssertableApplicationContext context);
    }

    private void run(Class<?> host, List<String> properties, Check check) throws IOException {
        try (RecordingLoader loader = new RecordingLoader(jobRoot, userRoot, hostRoot)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(host)
                    .withPropertyValues("spring.liquibase.enabled=false",
                            "topolith.modules.user.remote.url=http://localhost")
                    .withPropertyValues(properties.toArray(String[]::new)).run(check::check);
        }
    }

    private static void assertStarted(AssertableApplicationContext context) {
        assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
    }

    private static void assertTl0042(AssertableApplicationContext context, String className) {
        Throwable failure = context.getStartupFailure();
        assertNotNull(failure, "the context should have failed to start");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TopolithException topolith) {
                assertEquals(List.of(TopolithCode.TL0042),
                        topolith.diagnostics().stream().map(diagnostic -> diagnostic.code()).toList());
                assertTrue(topolith.getMessage().contains(className), topolith.getMessage());
                return;
            }
        }
        fail("no TopolithException in " + failure);
    }

    @Test
    void ac_mode_55_excluding_by_property_fails_with_tl0042_naming_the_class() throws IOException {
        run(Host.class, List.of("topolith.modules.job.mode=internal", "topolith.modules.user.mode=external",
                "spring.autoconfigure.exclude=" + TOPOLITH), context -> assertTl0042(context, TOPOLITH));
    }

    @Test
    void ac_mode_55_each_store_registration_exclusion_fails_with_tl0042() throws IOException {
        for (String store : List.of("R2dbc", "Mongo", "MyBatis")) {
            String name = "io.github.rey5137.topolith.spring.boot." + store
                    + "RepositoriesModulesAutoConfiguration";
            run(Host.class, List.of("topolith.modules.job.mode=internal", "topolith.modules.user.mode=none",
                    "spring.autoconfigure.exclude=" + name), context -> assertTl0042(context, name));
        }
    }

    @Test
    void ac_mode_55_excluding_by_annotation_fails_with_tl0042() throws IOException {
        run(ExcludingHost.class, List.of("topolith.modules.job.mode=internal", "topolith.modules.user.mode=none"),
                context -> assertTl0042(context, TOPOLITH));
    }

    @Test
    void ac_mode_55_excluding_with_only_none_and_external_modules_starts() throws IOException {
        List<String> modes = List.of("topolith.modules.job.mode=none", "topolith.modules.user.mode=external");
        run(ExcludingHost.class, modes, context -> {
            assertStarted(context);
            assertFalse(context.containsBean(JOB + ".JobComponent"));
        });
        run(Host.class, List.of("topolith.modules.job.mode=none", "topolith.modules.user.mode=external",
                "spring.autoconfigure.exclude=" + TOPOLITH), TopolithExclusionCheckTest::assertStarted);
    }

    @Test
    void ac_mode_55_excluding_a_configuration_that_activates_no_module_starts() throws IOException {
        run(Host.class, List.of("topolith.modules.job.mode=internal", "topolith.modules.user.mode=external",
                "spring.autoconfigure.exclude=" + RESILIENCE),
                context -> {
                    assertStarted(context);
                    assertTrue(context.containsBean(JOB + ".JobComponent"));
                });
    }

    @Test
    void ac_mode_55_the_check_ignores_unrelated_topology_errors() throws IOException {
        // the import filter registers the topology on its own; the listener is driven directly to see its effect
        try (RecordingLoader loader = new RecordingLoader(jobRoot, userRoot, hostRoot)) {
            for (String modes : List.of("topolith.modules.job.mode=none", "topolith.modules.job.mode=bogus")) {
                MockEnvironment environment = new MockEnvironment().withProperty("topolith.modules.user.mode", "none");
                String[] pair = modes.split("=");
                environment.setProperty(pair[0], pair[1]);
                TopolithExclusionCheck check = new TopolithExclusionCheck();
                check.setEnvironment(environment);
                check.setBeanClassLoader(loader);
                assertDoesNotThrow(() -> check.onAutoConfigurationImportEvent(
                        new AutoConfigurationImportEvent(this, List.of(), Set.of(TOPOLITH))), modes);
            }
        }
    }

    @Test
    void ac_mode_55_a_slice_that_never_includes_topolith_is_not_checked() throws IOException {
        run(SliceHost.class, List.of("topolith.modules.job.mode=internal", "topolith.modules.user.mode=external"),
                context -> {
                    assertStarted(context);
                    assertFalse(context.containsBean(JOB + ".JobComponent"));
                });
    }

    @Test
    void ac_mode_55_an_unexcluded_internal_module_still_starts() throws IOException {
        run(Host.class, List.of("topolith.modules.job.mode=internal", "topolith.modules.user.mode=external"),
                context -> {
                    assertStarted(context);
                    assertTrue(context.containsBean(JOB + ".JobComponent"));
                });
    }

    /**
     * D-111: every auto-configuration this module imports registers or activates module contributions (the entity,
     * repository and migration ones, and Topolith's own), so TL0042's list must equal the imports. The allowlist of
     * imports that activate nothing is empty today: an entry added to it needs the reason beside it.
     */
    @Test
    void ac_mode_55_the_activating_list_matches_the_auto_configuration_imports() throws IOException {
        Set<String> nonActivating = Set.of();
        Set<String> imports = new java.util.TreeSet<>();
        try (java.io.InputStream in = TopolithExclusionCheck.class.getClassLoader()
                .getResourceAsStream("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            reader.lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .filter(line -> line.startsWith("io.github.rey5137.topolith.spring.boot.")).forEach(imports::add);
        }
        imports.removeAll(nonActivating);
        assertFalse(imports.isEmpty());
        assertEquals(imports, new java.util.TreeSet<>(TopolithExclusionCheck.ACTIVATING));
    }
}
