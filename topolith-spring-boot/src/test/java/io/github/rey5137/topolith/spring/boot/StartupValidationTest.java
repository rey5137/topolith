package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.Host;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class StartupValidationTest {
    @TempDir
    Path root;

    private void descriptor(String kind, String id, String content) throws IOException {
        Path file = root.resolve("META-INF/topolith/" + kind + "/" + id + ".properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String module(String id, String contracts, String requires) {
        return "format-version=1\nid=" + id + "\npackage=fixture." + id + "\napi-packages=fixture.api\n"
                + "api-version=1.0\nprovides=" + contracts + "\nrequires=" + requires + "\n";
    }

    private void run(Consumer<AssertableApplicationContext> check, String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("spring.liquibase.enabled=false").withPropertyValues(properties)
                    .run(context -> check.accept(context));
        }
    }

    private TopolithException failure(AssertableApplicationContext context, TopolithCode code) {
        Throwable cause = context.getStartupFailure();
        while (cause != null && !(cause instanceof TopolithException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause);
        TopolithException failure = (TopolithException) cause;
        assertEquals(List.of(code), failure.diagnostics().stream().map(d -> d.code()).toList());
        assertFalse(failure.diagnostics().get(0).action().isBlank());
        return failure;
    }

    @Test
    void ac_val_01_endpoint_selection() throws Exception {
        descriptor("modules", "alpha", module("alpha", "", ""));
        for (String[] settings : List.of(new String[] {}, new String[] {"topolith.modules.alpha.remote.url= ",
                "topolith.modules.alpha.remote.service-id= "}, new String[] {
                "topolith.modules.alpha.remote.url=http://localhost", "topolith.modules.alpha.remote.service-id=x"})) {
            String[] properties = new String[settings.length + 1];
            properties[0] = "topolith.modules.alpha.mode=external";
            System.arraycopy(settings, 0, properties, 1, settings.length);
            run(context -> {
                String message = failure(context, TopolithCode.TL0003).getMessage();
                assertTrue(message.contains("alpha.remote.url"));
                assertTrue(message.contains("alpha.remote.service-id"));
            }, properties);
        }
        // a lone service id is a valid selection; without Cloud it fails with TL0009 instead (AC-EXT-06)
        run(context -> failure(context, TopolithCode.TL0009), "topolith.modules.alpha.mode=external",
                "topolith.modules.alpha.remote.service-id=discovery");
    }

    @Test
    void ac_ext_07_and_08_client_property_defaults_and_overrides() throws Exception {
        descriptor("modules", "alpha", module("alpha", "", ""));
        String mode = "topolith.modules.alpha.mode=external";
        String url = "topolith.modules.alpha.remote.url=http://localhost";
        run(context -> {
            assertEquals("http://localhost", context.getEnvironment().getProperty(
                    "spring.http.serviceclient.alpha.base-url"));
            assertEquals("2s", context.getEnvironment().getProperty("spring.http.serviceclient.alpha.connect-timeout"));
            assertEquals("5s", context.getEnvironment().getProperty("spring.http.serviceclient.alpha.read-timeout"));
        }, mode, url);
        run(context -> {
            assertEquals("3s", context.getEnvironment().getProperty("spring.http.serviceclient.alpha.connect-timeout"));
            assertEquals("9s", context.getEnvironment().getProperty("spring.http.serviceclient.alpha.read-timeout"));
        }, mode, url, "topolith.modules.alpha.remote.connect-timeout=3s",
                "spring.http.serviceclient.alpha.read-timeout=9s");
    }

    @Test
    void ac_ext_25_conflicting_client_properties_fail() throws Exception {
        descriptor("modules", "alpha", module("alpha", "", ""));
        String mode = "topolith.modules.alpha.mode=external";
        String url = "topolith.modules.alpha.remote.url=http://localhost";
        for (String[] pair : List.of(new String[] {url, "spring.http.serviceclient.alpha.base-url=http://other"},
                new String[] {"topolith.modules.alpha.remote.connect-timeout=3s",
                        "spring.http.serviceclient.alpha.connect-timeout=4s"},
                new String[] {"topolith.modules.alpha.remote.read-timeout=3s",
                        "spring.http.serviceclient.alpha.read-timeout=4s"})) {
            String[] properties = pair[0].equals(url) ? new String[] {mode, pair[0], pair[1]}
                    : new String[] {mode, url, pair[0], pair[1]};
            run(context -> {
                String message = failure(context, TopolithCode.TL0003).getMessage();
                assertTrue(message.contains(pair[0].split("=")[0]));
                assertTrue(message.contains(pair[1].split("=")[0]));
            }, properties);
        }
        run(context -> assertNull(context.getStartupFailure()), mode, url,
                "topolith.modules.alpha.remote.connect-timeout=2s",
                "spring.http.serviceclient.alpha.connect-timeout=2000ms");
    }

    @Test
    void ac_ext_25_equal_urls_are_not_a_conflict_and_malformed_durations_fail() throws Exception {
        descriptor("modules", "alpha", module("alpha", "", ""));
        run(context -> assertNull(context.getStartupFailure()), "topolith.modules.alpha.mode=external",
                "topolith.modules.alpha.remote.url=HTTP://Svc:8080/",
                "spring.http.serviceclient.alpha.base-url=http://svc:8080");
        run(context -> {
            String message = failure(context, TopolithCode.TL0003).getMessage();
            assertTrue(message.contains("topolith.modules.alpha.remote.connect-timeout"), message);
            assertTrue(message.contains("fast"), message);
        }, "topolith.modules.alpha.mode=external", "topolith.modules.alpha.remote.url=http://svc",
                "topolith.modules.alpha.remote.connect-timeout=fast",
                "spring.http.serviceclient.alpha.connect-timeout=3s");
    }

    @Test
    void ac_val_02_duplicate_contracts() throws Exception {
        descriptor("modules", "alpha", module("alpha", "fixture.api.Contract", ""));
        descriptor("modules", "beta", module("beta", "fixture.api.Contract", ""));
        run(context -> {
            String message = failure(context, TopolithCode.TL0005).getMessage();
            assertTrue(message.contains("fixture.api.Contract"));
            assertTrue(message.contains("alpha") && message.contains("beta"));
        }, "topolith.modules.alpha.mode=internal", "topolith.modules.beta.mode=internal");
        run(context -> assertNull(context.getStartupFailure()), "topolith.modules.alpha.mode=internal",
                "topolith.modules.beta.mode=none");
    }

    @Test
    void ac_val_03_malformed_handwritten_descriptor() throws Exception {
        descriptor("modules", "alpha", module("alpha", "", "").replace("api-version=1.0", "api-version=bad"));
        run(context -> assertTrue(failure(context, TopolithCode.TL0013).getMessage().contains("alpha.properties")));
    }

    @Test
    void ac_val_09_handwritten_descriptor_participates_in_validation() throws Exception {
        descriptor("modules", "alpha", module("alpha", "", "beta"));
        run(context -> failure(context, TopolithCode.TL0004), "topolith.modules.alpha.mode=internal");
        run(context -> assertNull(context.getStartupFailure()), "topolith.modules.alpha.mode=none");
    }

    @Test
    void ac_val_04_internal_contract_disagreement_has_fix() throws Exception {
        descriptor("modules", "alpha", module("alpha", "fixture.api.One", ""));
        descriptor("apis", "alpha", "format-version=1\nid=alpha\napi-packages=fixture.api\napi-version=1.0\n"
                + "provides=fixture.api.Two\n");
        run(context -> {
            String message = failure(context, TopolithCode.TL0022).getMessage();
            assertTrue(message.contains("alpha") && message.contains("fixture.api.One")
                    && message.contains("fixture.api.Two"));
        }, "topolith.modules.alpha.mode=internal");
        // external: the contracts are not compared (no TL0022), but an unloadable one is TL0026 (D-41)
        run(context -> {
            String failure = String.valueOf(context.getStartupFailure());
            assertTrue(failure.contains("TL0026") && !failure.contains("TL0022"));
        },
                "topolith.modules.alpha.mode=external",
                "topolith.modules.alpha.remote.url=http://localhost");
        descriptor("apis", "alpha", "format-version=1\nid=alpha\napi-packages=fixture.api\napi-version=1.0\n"
                + "provides=fixture.api.One\n");
        run(context -> assertNull(context.getStartupFailure()), "topolith.modules.alpha.mode=internal");
    }

    @Test
    void ac_val_14_internal_contract_disagreement_names_the_contracts_unique_to_each_side() throws Exception {
        descriptor("modules", "alpha", module("alpha", "fixture.api.One,fixture.api.Shared", ""));
        descriptor("apis", "alpha", "format-version=1\nid=alpha\napi-packages=fixture.api\napi-version=1.0\n"
                + "provides=fixture.api.Two,fixture.api.Shared\n");
        run(context -> {
            String message = failure(context, TopolithCode.TL0022).getMessage();
            assertTrue(message.contains("alpha") && message.contains("fixture.api.One")
                    && message.contains("fixture.api.Two"), message);
            assertFalse(message.contains("fixture.api.Shared"), message);
        }, "topolith.modules.alpha.mode=internal");
        run(context -> assertNull(context.getStartupFailure()), "topolith.modules.alpha.mode=none");
    }

    @ParameterizedTest
    @EnumSource(value = TopolithCode.class, names = {"TL0001", "TL0002", "TL0003", "TL0004", "TL0005", "TL0006",
            "TL0007", "TL0008", "TL0013", "TL0016", "TL0017", "TL0018", "TL0019", "TL0020", "TL0021",
            "TL0022", "TL0024", "TL0025"})
    void ac_ci_01_each_startup_failure_asserts_code_and_fix(TopolithCode code) throws Exception {
        List<Path> roots = new ArrayList<>();
        roots.add(root);
        List<String> properties = new ArrayList<>(List.of("spring.liquibase.enabled=false"));
        Class<?> host = Host.class;
        switch (code) {
            case TL0001 -> descriptor("modules", "alpha", module("alpha", "", ""));
            case TL0002 -> properties.add("topolith.modules.alpha.mode=internal");
            case TL0003 -> {
                descriptor("modules", "alpha", module("alpha", "", ""));
                properties.add("topolith.modules.alpha.mode=external");
            }
            case TL0004 -> {
                descriptor("modules", "alpha", module("alpha", "", "beta"));
                properties.add("topolith.modules.alpha.mode=internal");
            }
            case TL0005 -> {
                descriptor("modules", "alpha", module("alpha", "fixture.api.Contract", ""));
                descriptor("modules", "beta", module("beta", "fixture.api.Contract", ""));
                properties.addAll(List.of("topolith.modules.alpha.mode=internal",
                        "topolith.modules.beta.mode=internal"));
            }
            case TL0006 -> {
                descriptor("modules", "alpha", module("alpha", "", ""));
                Path other = Fixtures.dir(root, "other");
                Fixtures.module(other, "alpha", "fixture.alpha", List.of("fixture.api"));
                roots.add(other);
            }
            case TL0007 -> {
                Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
                host = io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.UserScanningHost.class;
                properties.add("topolith.modules.user.mode=none");
            }
            case TL0008 -> {
                descriptor("modules", "alpha", module("alpha", "", "beta"));
                descriptor("modules", "beta", module("beta", "", "alpha"));
            }
            case TL0013 -> descriptor("modules", "alpha", module("alpha", "", "").replace("api-version=1.0",
                    "api-version=bad"));
            case TL0016 -> properties.add("topolith.modules.alpha.mode=none");
            case TL0017 -> {
                descriptor("modules", "alpha", module("alpha", "", ""));
                descriptor("modules", "beta", module("beta", "", "").replace("package=fixture.beta",
                        "package=fixture.alpha.beta"));
            }
            case TL0018 -> {
                Fixtures.module(root, "ledger", Fixtures.LEDGER, List.of(Fixtures.LEDGER + ".api"));
                host = ModulePersistenceTest.OverlappingHost.class;
                properties.add("topolith.modules.ledger.mode=none");
            }
            case TL0019 -> {
                Fixtures.module(root, "rogue", Fixtures.ROGUE, List.of(Fixtures.ROGUE + ".api"));
                properties.add("topolith.modules.rogue.mode=internal");
            }
            case TL0020 -> {
                Fixtures.module(root, "ledger", Fixtures.LEDGER, List.of(Fixtures.LEDGER + ".api"));
                Fixtures.resource(root, "db/modules/ledger/V1__init.sql", "create table test_marker (id int);");
                properties.addAll(List.of("topolith.modules.ledger.mode=none", "spring.flyway.locations=classpath:db"));
            }
            case TL0021 -> {
                Fixtures.module(root, "ledger", Fixtures.LEDGER, List.of(Fixtures.LEDGER + ".api"));
                properties.addAll(List.of("topolith.modules.ledger.mode=internal", "spring.flyway.enabled=false",
                        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure."
                                + "HibernateJpaAutoConfiguration"));
            }
            case TL0022 -> {
                descriptor("modules", "alpha", module("alpha", "fixture.api.One", ""));
                descriptor("apis", "alpha", "format-version=1\nid=alpha\napi-packages=fixture.api\n"
                        + "api-version=1.0\nprovides=fixture.api.Two\n");
                properties.add("topolith.modules.alpha.mode=internal");
            }
            case TL0024 -> {
                Fixtures.module(root, "catalog", Fixtures.BASE + ".catalog", List.of(Fixtures.BASE + ".catalog.api"));
                host = io.github.rey5137.topolith.spring.boot.fixture.quiet.QuietHost.class;
                properties.addAll(List.of("topolith.modules.catalog.mode=internal",
                        "spring.data.jpa.repositories.enabled=false"));
            }
            case TL0025 -> {
                Fixtures.module(root, "ledger", Fixtures.LEDGER, List.of(Fixtures.LEDGER + ".api"));
                Fixtures.module(root, "catalog", Fixtures.BASE + ".catalog", List.of(Fixtures.BASE + ".catalog.api"));
                properties.addAll(List.of("topolith.standalone=ledger", "topolith.modules.ledger.mode=internal",
                        "topolith.modules.catalog.mode=internal"));
            }
            default -> throw new AssertionError(code);
        }
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(roots.toArray(Path[]::new))) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(host)
                    .withPropertyValues(properties.toArray(String[]::new)).run(context -> {
                        Throwable cause = context.getStartupFailure();
                        while (cause != null && !(cause instanceof TopolithException)) {
                            cause = cause.getCause();
                        }
                        assertNotNull(cause, code.toString());
                        TopolithException failure = (TopolithException) cause;
                        assertFalse(failure.diagnostics().isEmpty());
                        failure.diagnostics().forEach(diagnostic -> {
                            assertEquals(code, diagnostic.code());
                            assertFalse(diagnostic.action().isBlank(), code.toString());
                        });
                    });
        }
    }
}
