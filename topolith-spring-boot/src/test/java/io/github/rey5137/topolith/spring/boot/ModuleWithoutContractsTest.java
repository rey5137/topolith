package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.Fixtures.RecordingLoader;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.Host;
import io.github.rey5137.topolith.spring.boot.fixture.shared.FixtureEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static io.github.rey5137.topolith.spring.boot.Fixtures.BASE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** M8.2 acceptance tests: a module without contracts, which has no API packages and no API descriptor (D-115). */
class ModuleWithoutContractsTest {
    private static final String JOB = BASE + ".job";

    @TempDir
    Path temp;
    Path jobRoot;
    Path hostRoot;

    @BeforeEach
    void roots() throws IOException {
        jobRoot = Fixtures.dir(temp, "job");
        Fixtures.module(jobRoot, "job", JOB, List.of());
        hostRoot = Fixtures.dir(temp, "host");
        FixtureEvent.RECEIVED.clear();
    }

    private interface Check {
        void check(AssertableApplicationContext context);
    }

    private void run(String mode, Check check) throws IOException {
        try (RecordingLoader loader = new RecordingLoader(jobRoot, hostRoot)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.job.mode=" + mode)
                    .run(check::check);
        }
    }

    private static List<TopolithCode> codes(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        assertNotNull(failure, "the context should have failed to start");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TopolithException topolith) {
                assertTrue(topolith.getMessage().contains("'job'"), topolith.getMessage());
                return topolith.diagnostics().stream().map(diagnostic -> diagnostic.code()).toList();
            }
        }
        return fail("no TopolithException in " + failure);
    }

    @Test
    void ac_desc_18_internal_runs_its_beans_and_listeners_without_an_api_artifact() throws IOException {
        run("internal", context -> {
            assertTrue(context.getStartupFailure() == null, String.valueOf(context.getStartupFailure()));
            assertTrue(context.containsBean(JOB + ".JobComponent"));
            context.publishEvent(new FixtureEvent());
            assertEquals(List.of("job"), FixtureEvent.RECEIVED);
            assertTrue(context.getBean(Topology.class).apiPackages().isEmpty());
        });
    }

    @Test
    void ac_desc_18_none_leaves_no_trace() throws IOException {
        run("none", context -> {
            assertTrue(context.getStartupFailure() == null, String.valueOf(context.getStartupFailure()));
            assertFalse(context.containsBean(JOB + ".JobComponent"));
            assertFalse(context.containsBean(JOB + ".JobListener"));
            context.publishEvent(new FixtureEvent());
            assertTrue(FixtureEvent.RECEIVED.isEmpty());
        });
    }

    @Test
    void ac_mode_62_external_and_loopback_fail_with_tl0003_naming_the_module() throws IOException {
        for (String mode : List.of("external", "loopback")) {
            run(mode, context -> assertEquals(List.of(TopolithCode.TL0003), codes(context), mode));
        }
    }
}
