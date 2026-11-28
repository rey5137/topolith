package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.Fixtures.RecordingLoader;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.Host;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static io.github.rey5137.topolith.spring.boot.Fixtures.BASE;
import static io.github.rey5137.topolith.spring.boot.Fixtures.USER;
import static io.github.rey5137.topolith.spring.boot.Fixtures.USER_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** M8.2 acceptance tests: a host's {@code mybatis.mapper-locations} must not reach a module's XML (D-114). */
class MapperLocationsCheckTest {
    private static final String PATH = BASE.replace('.', '/');
    private static final String MODULE_XML = PATH + "/user/mapper/UserMapper.xml";
    private static final String HOST_XML = PATH + "/hostmappers/HostMapper.xml";

    @TempDir
    Path temp;
    Path userRoot;
    Path hostRoot;

    @BeforeEach
    void roots() throws IOException {
        userRoot = Fixtures.dir(temp, "user");
        Fixtures.module(userRoot, "user", USER, List.of(USER_API));
        Fixtures.resource(userRoot, MODULE_XML, "<mapper namespace=\"user\"/>");
        hostRoot = Fixtures.dir(temp, "host");
        Fixtures.resource(hostRoot, HOST_XML, "<mapper namespace=\"host\"/>");
    }

    private interface Check {
        void check(AssertableApplicationContext context);
    }

    private void run(String mode, String locations, Check check) throws IOException {
        try (RecordingLoader loader = new RecordingLoader(userRoot, hostRoot)) {
            ApplicationContextRunner runner = new ApplicationContextRunner().withClassLoader(loader)
                    .withUserConfiguration(Host.class)
                    .withPropertyValues("spring.liquibase.enabled=false",
                            "spring.autoconfigure.exclude=org.mybatis.spring.boot.autoconfigure."
                                    + "MybatisAutoConfiguration",
                            "topolith.modules.user.mode=" + mode,
                            "topolith.modules.user.remote.url=http://localhost");
            if (locations != null) {
                runner = runner.withPropertyValues("mybatis.mapper-locations=" + locations);
            }
            runner.run(check::check);
        }
    }

    private void runStandalone(String locations, Check check) throws IOException {
        try (RecordingLoader loader = new RecordingLoader(userRoot, hostRoot)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("spring.liquibase.enabled=false",
                            "spring.autoconfigure.exclude=org.mybatis.spring.boot.autoconfigure."
                                    + "MybatisAutoConfiguration",
                            "topolith.standalone=user",
                            "mybatis.mapper-locations=" + locations)
                    .run(check::check);
        }
    }

    private static void assertTl0043(AssertableApplicationContext context, String location) {
        Throwable failure = context.getStartupFailure();
        assertNotNull(failure, "the context should have failed to start");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TopolithException topolith) {
                assertEquals(List.of(TopolithCode.TL0043),
                        topolith.diagnostics().stream().map(diagnostic -> diagnostic.code()).toList());
                assertTrue(topolith.getMessage().contains(location), topolith.getMessage());
                assertTrue(topolith.getMessage().contains("UserMapper.xml"), topolith.getMessage());
                assertTrue(topolith.getMessage().contains("module 'user'"), topolith.getMessage());
                return;
            }
        }
        fail("no TopolithException in " + failure);
    }

    private static void assertStarted(AssertableApplicationContext context) {
        assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
    }

    @Test
    void ac_mode_61_a_location_reaching_a_none_modules_xml_fails_with_tl0043() throws IOException {
        String location = "classpath*:" + PATH + "/**/*.xml";
        run("none", location, context -> assertTl0043(context, location));
    }

    @Test
    void ac_mode_61_a_location_reaching_an_internal_modules_xml_fails_with_tl0043() throws IOException {
        String location = "classpath*:" + PATH + "/user/**/*.xml";
        run("internal", location, context -> assertTl0043(context, location));
    }

    @Test
    void ac_mode_61_one_bad_pattern_among_several_fails() throws IOException {
        String location = "classpath:" + MODULE_XML;
        run("external", "classpath*:" + HOST_XML + ", " + location, context -> assertTl0043(context, location));
    }

    @Test
    void ac_mode_61_a_pattern_reaching_only_the_hosts_xml_starts() throws IOException {
        Fixtures.resource(hostRoot, PATH + "/username/HostMapper.xml", "<mapper namespace=\"sibling\"/>");
        for (String mode : List.of("none", "internal", "external")) {
            run(mode, "classpath*:" + PATH + "/hostmappers/*.xml", MapperLocationsCheckTest::assertStarted);
            run(mode, "classpath*:" + PATH + "/username/*.xml", MapperLocationsCheckTest::assertStarted);
        }
    }

    @Test
    void ac_mode_61_the_standalone_modules_own_xml_is_exempt() throws IOException {
        runStandalone("classpath*:" + PATH + "/user/**/*.xml", MapperLocationsCheckTest::assertStarted);
    }

    @Test
    void ac_mode_61_an_unset_or_unmatched_property_starts() throws IOException {
        run("none", null, MapperLocationsCheckTest::assertStarted);
        run("none", "classpath*:nothing/here/*.xml", MapperLocationsCheckTest::assertStarted);
    }

    private void runPackages(String mode, String property, String value, Check check) throws IOException {
        try (RecordingLoader loader = new RecordingLoader(userRoot, hostRoot)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("spring.liquibase.enabled=false",
                            "spring.autoconfigure.exclude=org.mybatis.spring.boot.autoconfigure."
                                    + "MybatisAutoConfiguration",
                            mode.equals("standalone") ? "topolith.standalone=user" : "topolith.modules.user.mode=" + mode,
                            "topolith.modules.user.remote.url=http://localhost", property + "=" + value)
                    .run(check::check);
        }
    }

    private static void assertPackageTl0043(AssertableApplicationContext context, String property) {
        Throwable failure = context.getStartupFailure();
        assertNotNull(failure, "the context should have failed to start");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TopolithException topolith) {
                assertEquals(List.of(TopolithCode.TL0043),
                        topolith.diagnostics().stream().map(diagnostic -> diagnostic.code()).toList());
                assertTrue(topolith.getMessage().contains(property) && topolith.getMessage().contains("module 'user'"),
                        topolith.getMessage());
                return;
            }
        }
        fail("no TopolithException in " + failure);
    }

    @Test
    void ac_mode_61_a_type_package_equal_to_a_parent_of_or_inside_a_non_internal_module_fails_with_tl0043()
            throws IOException {
        for (String property : List.of("mybatis.type-aliases-package", "mybatis.type-handlers-package")) {
            for (String mode : List.of("none", "external")) {
                for (String value : List.of(USER, BASE, USER + ".mapper", "com.other, " + BASE + ".user.;x.y",
                        "com.other;" + USER)) {
                    runPackages(mode, property, value, context -> assertPackageTl0043(context, property));
                }
            }
        }
    }

    @Test
    void ac_mode_61_a_sibling_or_non_boundary_type_package_starts() throws IOException {
        for (String property : List.of("mybatis.type-aliases-package", "mybatis.type-handlers-package")) {
            for (String value : List.of(BASE + ".username", BASE + ".use", BASE + ".hostmappers", "", " , ")) {
                runPackages("none", property, value, MapperLocationsCheckTest::assertStarted);
            }
        }
    }

    @Test
    void ac_mode_61_an_internal_or_standalone_module_is_exempt_from_the_type_package_check() throws IOException {
        for (String mode : List.of("internal", "standalone")) {
            runPackages(mode, "mybatis.type-aliases-package", BASE, MapperLocationsCheckTest::assertStarted);
            runPackages(mode, "mybatis.type-handlers-package", USER, MapperLocationsCheckTest::assertStarted);
        }
    }

    @Test
    void ac_mode_61_a_whitespace_separated_type_package_entry_is_checked() throws IOException {
        runPackages("none", "mybatis.type-aliases-package", "com.other " + USER,
                context -> assertPackageTl0043(context, "mybatis.type-aliases-package"));
    }
}
