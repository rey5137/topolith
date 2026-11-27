package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.cover.api.GoodContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.cover.api.PartialContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.tally.api.TallyContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M8.3 route coverage (D-126): every contract method of an exposed or loopback module has a module route. */
class RouteCoverageTest {
    private static final String TALLY = TallyContract.class.getName();

    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        ContractExceptionsTest.contracts(root, "account", AccountContract.class);
        ContractExceptionsTest.contracts(root, "tally", TallyContract.class);
    }

    private void serve(ContextConsumer<AssertableWebApplicationContext> check, String... properties)
            throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(ErrorHost.class)
                    .withPropertyValues("server.port=0", "spring.liquibase.enabled=false",
                            "topolith.modules.account.mode=none", "topolith.modules.tally.mode=none")
                    .withPropertyValues(properties).run(check);
        }
    }

    @Test
    void ac_ext_82_an_exposed_or_loopback_module_served_only_by_a_service_fails_with_tl0044() throws Exception {
        for (String[] properties : List.of(new String[] {"topolith.modules.tally.mode=loopback"},
                new String[] {"topolith.modules.tally.mode=internal", "topolith.modules.tally.expose.enabled=true"})) {
            serve(context -> LoopbackTest.fails(context.getStartupFailure(), TopolithCode.TL0044, "module 'tally'",
                    "no module route serves " + TALLY + " method(s) [count(), reset()]", "@RestController"),
                    properties);
        }
    }

    @Test
    void ac_ext_82_a_module_whose_controller_implements_every_contract_method_starts() throws Exception {
        for (String[] properties : List.of(new String[] {"topolith.modules.account.mode=loopback"},
                new String[] {"topolith.modules.account.mode=internal",
                    "topolith.modules.account.expose.enabled=true"})) {
            serve(context -> assertNull(context.getStartupFailure()), properties);
        }
    }

    @Test
    void ac_ext_82_an_unexposed_none_or_external_module_is_not_checked() throws Exception {
        for (String mode : List.of("internal", "none", "external")) {
            serve(context -> {
                assertNull(context.getStartupFailure(), mode);
                assertTrue(context.getBeansOfType(TallyContract.class).size() <= 1, mode);
            }, "topolith.modules.tally.mode=" + mode, "external".equals(mode)
                    ? "topolith.modules.tally.remote.url=http://localhost:1" : "topolith.modules.tally.mode=" + mode);
        }
    }

    private static final String COVER = Fixtures.ERRORS + ".cover";
    private static final String[] COVER_EXPOSED = {"topolith.modules.cover.mode=internal",
        "topolith.modules.cover.expose.enabled=true"};

    @Test
    void ac_ext_82_bridged_inherited_and_redeclared_object_methods_are_covered_by_the_controller() throws Exception {
        // put is generic (a bridge in the controller), ping inherited, toString a redeclared Object method
        ContractExceptionsTest.contracts(root, "cover", COVER, COVER + ".api", GoodContract.class);
        serve(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(200, ProviderErrorsTest.get(context, "/cover/good/ping").status());
        }, COVER_EXPOSED);
    }

    @Test
    void ac_ext_82_an_unmapped_overload_or_override_fails_with_tl0044_naming_only_those() throws Exception {
        ContractExceptionsTest.contracts(root, "cover", COVER, COVER + ".api", PartialContract.class);
        serve(context -> {
            TopolithException failure = LoopbackTest.fails(context.getStartupFailure(), TopolithCode.TL0044,
                    "module 'cover'", PartialContract.class.getName() + " method(s) [find(String, int), pending()]");
            assertFalse(failure.getMessage().contains("find(String),"), failure.getMessage());
        }, COVER_EXPOSED);
    }

    @Test
    void inv_2_without_an_internal_module_there_is_no_coverage_check() throws Exception {
        serve(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("topolithRouteCoverage"));
        });
    }
}
