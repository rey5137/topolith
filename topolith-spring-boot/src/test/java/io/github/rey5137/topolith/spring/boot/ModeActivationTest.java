package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.Fixtures.RecordingLoader;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.Host;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.ParentScanningHost;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.UserScanningHost;
import io.github.rey5137.topolith.spring.boot.fixture.shared.FixtureEvent;
import io.github.rey5137.topolith.spring.boot.fixture.user.UserComponent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static io.github.rey5137.topolith.spring.boot.Fixtures.BASE;
import static io.github.rey5137.topolith.spring.boot.Fixtures.OUTSIDE;
import static io.github.rey5137.topolith.spring.boot.Fixtures.PAYMENT;
import static io.github.rey5137.topolith.spring.boot.Fixtures.USER;
import static io.github.rey5137.topolith.spring.boot.Fixtures.USER_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** M1.3 acceptance tests: mode resolution and inclusion-based activation, end to end on a real Boot context. */
class ModeActivationTest {
    private interface Assertions {
        void check(AssertableApplicationContext context, RecordingLoader loader) throws Exception;
    }

    @TempDir
    Path temp;
    Path userRoot;
    Path paymentRoot;
    Path hostRoot;

    @BeforeEach
    void roots() throws IOException {
        userRoot = Fixtures.dir(temp, "user");
        Fixtures.module(userRoot, "user", USER, List.of(USER_API));
        Fixtures.imports(userRoot, USER + ".UserAutoConfiguration", OUTSIDE + ".ColocatedAutoConfiguration");
        paymentRoot = Fixtures.dir(temp, "payment");
        Fixtures.module(paymentRoot, "payment", PAYMENT, List.of(PAYMENT + ".api"), "user");
        hostRoot = Fixtures.dir(temp, "host");
        Fixtures.imports(hostRoot, "io.github.rey5137.topolith.spring.a_later.LaterAutoConfiguration");
        FixtureEvent.RECEIVED.clear();
    }

    private void run(Class<?> host, List<Path> roots, List<String> properties, Assertions assertions)
            throws Exception {
        try (RecordingLoader loader = new RecordingLoader(roots.toArray(Path[]::new))) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(host)
                    .withPropertyValues("spring.liquibase.enabled=false")
                    .withPropertyValues("topolith.modules.user.remote.url=http://localhost",
                            "topolith.modules.payment.remote.url=http://localhost")
                    .withPropertyValues(properties.toArray(String[]::new)).run(context -> {
                        try {
                            assertions.check(context, loader);
                        } catch (RuntimeException | Error e) {
                            throw e;
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    });
        }
    }

    private void run(List<String> properties, Assertions assertions) throws Exception {
        run(Host.class, List.of(userRoot, paymentRoot, hostRoot), properties, assertions);
    }

    private static TopolithException failure(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        assertNotNull(failure, "the context should have failed to start");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TopolithException topolith) {
                return topolith;
            }
        }
        return fail("no TopolithException in the cause chain of " + failure);
    }

    private static List<TopolithCode> codes(AssertableApplicationContext context) {
        return failure(context).diagnostics().stream().map(diagnostic -> diagnostic.code()).toList();
    }

    private static boolean has(AssertableApplicationContext context, String className) {
        return context.containsBean(className);
    }

    /** What a module leaves in a started context: beans, listener calls and scheduled tasks. */
    private record Footprint(List<String> beans, int listenerCalls, long scheduledTasks) {
        boolean empty() {
            return beans.isEmpty() && listenerCalls == 0 && scheduledTasks == 0;
        }
    }

    /** The class that declares a definition: its own class, or for a {@code @Bean} method its configuration class. */
    private static String declaringClassName(AssertableApplicationContext context, BeanDefinition definition) {
        if (definition instanceof AnnotatedBeanDefinition annotated && annotated.getFactoryMethodMetadata() != null) {
            return annotated.getFactoryMethodMetadata().getDeclaringClassName();
        }
        String factoryBean = definition.getFactoryBeanName();
        if (definition.getBeanClassName() == null && factoryBean != null
                && context.containsBeanDefinition(factoryBean)) {
            return context.getBeanFactory().getBeanDefinition(factoryBean).getBeanClassName();
        }
        return definition.getBeanClassName();
    }

    private static Footprint footprint(AssertableApplicationContext context, String packagePrefix) {
        List<String> beans = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            BeanDefinition definition = context.getBeanFactory().getBeanDefinition(name);
            String className = declaringClassName(context, definition);
            if (name.startsWith(packagePrefix) || className != null && className.startsWith(packagePrefix)) {
                beans.add(name);
            }
        }
        FixtureEvent.RECEIVED.clear();
        context.publishEvent(new FixtureEvent());
        long tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .filter(task -> task.toString().startsWith(packagePrefix)).count();
        String module = packagePrefix.substring(packagePrefix.lastIndexOf('.') + 1);
        return new Footprint(beans, (int) FixtureEvent.RECEIVED.stream().filter(module::equals).count(), tasks);
    }

    @Test
    void ac_mode_01_each_explicit_mode_resolves_to_its_declared_value() throws Exception {
        run(List.of("topolith.modules.user.mode=internal", "topolith.modules.payment.mode= External "),
                (context, loader) -> {
                    assertNull(context.getStartupFailure());
                    Topology topology = context.getBean("topolithTopology", Topology.class);
                    assertEquals(Mode.INTERNAL, topology.mode("user"));
                    assertEquals(Mode.EXTERNAL, topology.mode("payment"));
                    assertSame(topology, context.getBean(Topology.class));
                });
        run(List.of("topolith.modules.user.mode=NONE", "topolith.modules.payment.mode=none"), (context, loader) -> {
            Topology topology = context.getBean(Topology.class);
            assertEquals(Mode.NONE, topology.mode("user"));
            assertEquals(Mode.NONE, topology.mode("payment"));
        });
    }

    @Test
    void ac_mode_01_the_mode_is_declared_whether_or_not_the_implementation_jar_is_present() throws Exception {
        Path apiRoot = Fixtures.dir(temp, "api");
        Fixtures.api(apiRoot, "user", USER_API);
        run(Host.class, List.of(apiRoot, hostRoot), List.of("topolith.modules.user.mode=external"),
                (context, loader) -> assertEquals(Mode.EXTERNAL, context.getBean(Topology.class).mode("user")));
        run(Host.class, List.of(userRoot, hostRoot), List.of("topolith.modules.user.mode=external"),
                (context, loader) -> assertEquals(Mode.EXTERNAL, context.getBean(Topology.class).mode("user")));
        run(Host.class, List.of(userRoot, hostRoot), List.of("topolith.modules.user.mode=none"),
                (context, loader) -> assertEquals(Mode.NONE, context.getBean(Topology.class).mode("user")));
    }

    @Test
    void ac_mode_01_a_classpath_without_descriptors_activates_nothing() throws Exception {
        run(Host.class, List.of(hostRoot), List.of(), (context, loader) -> {
            assertTrue(context.getBean(Topology.class).known().isEmpty());
            assertTrue(footprint(context, BASE + ".user").empty());
        });
    }

    @Test
    void ac_mode_02_a_known_module_without_a_mode_fails_with_tl0001() throws Exception {
        run(List.of("topolith.modules.user.mode=internal"), (context, loader) -> {
            TopolithException failure = failure(context);
            assertEquals(List.of(TopolithCode.TL0001), codes(context));
            assertTrue(failure.getMessage().contains("module 'payment'"), failure.getMessage());
            assertTrue(failure.getMessage().contains("topolith.modules.payment.mode"), failure.getMessage());
        });
        run(List.of(), (context, loader) -> assertEquals(List.of(TopolithCode.TL0001, TopolithCode.TL0001),
                codes(context)));
    }

    @Test
    void ac_mode_02_a_key_holding_only_other_settings_is_not_a_mode() throws Exception {
        run(List.of("topolith.modules.user.mode=none", "topolith.modules.payment.remote.url=http://x"),
                (context, loader) -> assertEquals(List.of(TopolithCode.TL0001), codes(context)));
    }

    @Test
    void ac_mode_03_a_standalone_service_defaults_only_its_own_module_to_internal() throws Exception {
        run(Host.class, List.of(userRoot, hostRoot), List.of("topolith.standalone=user"), (context, loader) -> {
            assertEquals(Mode.INTERNAL, context.getBean(Topology.class).mode("user"));
            assertTrue(has(context, USER + ".UserComponent"));
        });
        run(List.of("topolith.standalone=user"), (context, loader) -> {
            assertEquals(List.of(TopolithCode.TL0001), codes(context));
            assertTrue(failure(context).getMessage().contains("module 'payment'"));
        });
        run(Host.class, List.of(userRoot, hostRoot), List.of("topolith.standalone=user",
                "topolith.modules.user.mode=none"), (context, loader) -> {
                    assertEquals(Mode.NONE, context.getBean(Topology.class).mode("user"));
                    assertFalse(has(context, USER + ".UserComponent"));
                });
    }

    @Test
    void ac_mode_31_a_standalone_service_with_another_internal_module_fails_with_tl0025() throws Exception {
        run(Host.class, List.of(userRoot, paymentRoot, hostRoot), List.of("topolith.standalone=user",
                "topolith.modules.payment.mode=internal"), (context, loader) -> {
            assertEquals(List.of(TopolithCode.TL0025), codes(context));
            String message = failure(context).getMessage();
            assertTrue(message.contains("'user'") && message.contains("'payment'"), message);
        });
    }

    @Test
    void ac_mode_31_a_standalone_service_with_other_modules_external_or_none_starts() throws Exception {
        for (String mode : List.of("external", "none")) {
            run(Host.class, List.of(userRoot, paymentRoot, hostRoot), List.of("topolith.standalone=user",
                    "topolith.modules.payment.mode=" + mode), (context, loader) -> {
                assertNull(context.getStartupFailure());
                assertEquals(Mode.INTERNAL, context.getBean(Topology.class).mode("user"));
            });
        }
    }

    @Test
    void ac_mode_31_an_internal_mode_without_a_descriptor_is_tl0002_not_tl0025() throws Exception {
        run(Host.class, List.of(userRoot, hostRoot), List.of("topolith.standalone=user",
                "topolith.modules.payment.mode=internal"), (context, loader) ->
                assertEquals(List.of(TopolithCode.TL0002), codes(context)));
    }

    @Test
    void ac_mode_03_a_standalone_id_without_its_module_descriptor_fails_with_tl0002() throws Exception {
        run(Host.class, List.of(hostRoot), List.of("topolith.standalone=user"),
                (context, loader) -> assertEquals(List.of(TopolithCode.TL0002), codes(context)));
    }

    @Test
    void ac_mode_04_an_internal_modules_configuration_and_packages_are_imported() throws Exception {
        run(List.of("topolith.modules.user.mode=internal", "topolith.modules.payment.mode=none"),
                (context, loader) -> {
                    for (String type : List.of("UserComponent", "UserController", "UserListener", "UserTask",
                            "UserConfiguration")) {
                        assertTrue(has(context, USER + "." + type), type);
                    }
                    assertTrue(has(context, "userSharedService"));
                    assertEquals(1, context.getBeanNamesForType(UserComponent.class).length);
                    assertFalse(has(context, USER_API + ".ApiComponent"), "an api package is never imported");
                    assertEquals(1, context.getBeanNamesForType(
                            io.github.rey5137.topolith.spring.boot.fixture.user.UserAutoConfiguration.class).length);
                    assertTrue(has(context, "userAutoConfigurationMarker"));
                    assertTrue(footprint(context, PAYMENT).empty());
                    assertEquals(List.of("user"), FixtureEvent.RECEIVED);
                });
    }

    @Test
    void ac_mode_04_a_module_bean_is_seen_by_the_conditions_of_a_later_auto_configuration() throws Exception {
        run(List.of("topolith.modules.user.mode=internal", "topolith.modules.payment.mode=none",
                "test.later.enabled=true"), (context, loader) ->
                assertEquals(List.of(TopolithCode.TL0031), codes(context)));
        run(List.of("topolith.modules.user.mode=none", "topolith.modules.payment.mode=none",
                "test.later.enabled=true"), (context, loader) -> {
            assertFalse(has(context, "userSharedService"));
            assertTrue(has(context, "fallbackSharedService"));
        });
    }

    @Test
    void ac_mode_04_imported_classes_get_fully_qualified_bean_names() throws Exception {
        run(List.of("topolith.modules.user.mode=internal", "topolith.modules.payment.mode=none"),
                (context, loader) -> {
                    assertTrue(has(context, USER + ".UserComponent"));
                    assertFalse(has(context, "userComponent"));
                });
    }

    @Test
    void ac_mode_04_a_standalone_host_scanning_its_own_module_package_gets_each_bean_once() throws Exception {
        run(UserScanningHost.class, List.of(userRoot, hostRoot), List.of("topolith.standalone=user"),
                (context, loader) -> {
                    assertEquals(1, context.getBeanNamesForType(UserComponent.class).length);
                    assertEquals(1, footprint(context, USER).scheduledTasks());
                    assertEquals(1, context.getBeansOfType(
                            io.github.rey5137.topolith.spring.boot.fixture.user.UserConfiguration.class).size());
                });
    }

    @Test
    void ac_mode_05_a_missing_internal_implementation_fails_with_tl0002_and_a_fix() throws Exception {
        Path apiRoot = Fixtures.dir(temp, "api");
        Fixtures.api(apiRoot, "user", USER_API);
        run(Host.class, List.of(apiRoot, hostRoot), List.of("topolith.modules.user.mode=internal"),
                (context, loader) -> {
                    assertEquals(List.of(TopolithCode.TL0002), codes(context));
                    String message = failure(context).getMessage();
                    assertTrue(message.contains("META-INF/topolith/modules/user.properties"), message);
                    assertTrue(message.contains("external or none"), message);
                });
    }

    @Test
    void ac_mode_06_a_non_internal_module_contributes_no_component_or_owned_auto_configuration() throws Exception {
        for (String mode : List.of("none", "external")) {
            run(List.of("topolith.modules.user.mode=" + mode, "topolith.modules.payment.mode=none",
                    "test.later.enabled=true"),
                    (context, loader) -> {
                        assertTrue(footprint(context, BASE + ".user").empty(), mode);
                        assertFalse(has(context, "userAutoConfigurationMarker"), mode);
                        assertFalse(has(context, "colocatedAutoConfigurationMarker"), mode);
                        assertTrue(has(context, "fallbackSharedService"), mode);
                        assertEquals(List.of(), loader.requested(BASE + ".user"), "no class of the module is loaded");
                        assertEquals(List.of(), loader.requested(OUTSIDE));
                    });
        }
    }

    @Test
    void ac_mode_07_a_none_module_contributes_no_beans_routes_listeners_or_scheduled_tasks() throws Exception {
        run(List.of("topolith.modules.user.mode=internal", "topolith.modules.payment.mode=none"),
                (context, loader) -> {
                    Footprint present = footprint(context, BASE + ".user");
                    assertTrue(present.beans().size() >= 5, present.toString());
                    assertEquals(1, present.listenerCalls());
                    assertEquals(1, present.scheduledTasks());
                });
        run(List.of("topolith.modules.user.mode=none", "topolith.modules.payment.mode=none"), (context, loader) -> {
            Footprint absent = footprint(context, BASE + ".user");
            assertTrue(absent.empty(), absent.toString());
        });
    }

    @Test
    void ac_mode_08_an_active_module_requiring_a_none_module_fails_with_tl0004() throws Exception {
        run(List.of("topolith.modules.user.mode=none", "topolith.modules.payment.mode=internal"),
                (context, loader) -> {
                    assertEquals(List.of(TopolithCode.TL0004), codes(context));
                    String message = failure(context).getMessage();
                    assertTrue(message.contains("'payment' requires 'user'"), message);
                    assertTrue(message.contains("its mode is none"), message);
                });
    }

    @Test
    void ac_mode_16_an_external_payment_requiring_user_starts_with_user_none() throws Exception {
        run(List.of("topolith.modules.user.mode=none", "topolith.modules.payment.mode=external"),
                (context, loader) -> {
                    assertEquals(Mode.EXTERNAL, context.getBean(Topology.class).mode("payment"));
                    assertTrue(footprint(context, BASE + ".payment").empty());
                    assertTrue(footprint(context, BASE + ".user").empty());
                });
    }

    @Test
    void ac_mode_16_an_external_module_counts_as_available_for_an_internal_one() throws Exception {
        run(List.of("topolith.modules.user.mode=external", "topolith.modules.payment.mode=internal"),
                (context, loader) -> {
                    assertTrue(has(context, PAYMENT + ".PaymentComponent"));
                    assertTrue(footprint(context, BASE + ".user").empty());
                });
    }

    @Test
    void ac_mode_21_a_mode_for_an_id_without_a_descriptor_fails_with_tl0016() throws Exception {
        run(List.of("topolith.modules.user.mode=none", "topolith.modules.payment.mode=none",
                "topolith.modules.usr.mode=none"), (context, loader) -> {
                    assertEquals(List.of(TopolithCode.TL0016), codes(context));
                    assertTrue(failure(context).getMessage().contains("'usr'"), failure(context).getMessage());
                });
    }

    @Test
    void ac_mode_21_an_internal_mode_for_an_id_without_a_descriptor_fails_with_tl0002() throws Exception {
        run(List.of("topolith.modules.user.mode=none", "topolith.modules.payment.mode=none",
                "topolith.modules.usr.mode=internal"),
                (context, loader) -> assertEquals(List.of(TopolithCode.TL0002), codes(context)));
    }

    @Test
    void ac_mode_22_an_invalid_mode_value_fails_with_tl0001() throws Exception {
        run(List.of("topolith.modules.user.mode=loopback", "topolith.modules.payment.mode=none"),
                (context, loader) -> {
                    assertEquals(List.of(TopolithCode.TL0001), codes(context));
                    assertTrue(failure(context).getMessage().contains("'loopback'"), failure(context).getMessage());
                });
        run(List.of("topolith.modules.user.mode=", "topolith.modules.payment.mode=none"),
                (context, loader) -> assertEquals(List.of(TopolithCode.TL0001), codes(context)));
    }

    @Test
    void ac_mode_22_a_module_value_that_is_not_a_mode_setting_fails_with_tl0001_not_a_bind_error() throws Exception {
        run(List.of("topolith.modules.user=internal", "topolith.modules.payment.mode=none"), (context, loader) -> {
            assertEquals(List.of(TopolithCode.TL0001), codes(context));
            String message = failure(context).getMessage();
            assertTrue(message.contains("'topolith.modules.user'") && message.contains("module 'user'"), message);
            assertTrue(message.contains("topolith.modules.user.mode"), message);
        });
    }

    @Test
    void ac_desc_10_nested_module_packages_fail_startup_with_tl0017() throws Exception {
        Path innerRoot = Fixtures.dir(temp, "inner");
        Fixtures.module(innerRoot, "inner", USER + ".sub", List.of(BASE + ".innerapi"));
        run(Host.class, List.of(userRoot, innerRoot, hostRoot), List.of("topolith.modules.user.mode=none",
                "topolith.modules.inner.mode=none"), (context, loader) -> {
                    assertEquals(List.of(TopolithCode.TL0017), codes(context));
                    assertTrue(failure(context).getMessage().contains("'inner'"), failure(context).getMessage());
                });
    }

    @Test
    void ac_mode_23_a_co_located_auto_configuration_outside_the_package_is_removed_for_a_non_internal_module()
            throws Exception {
        run(List.of("topolith.modules.user.mode=none", "topolith.modules.payment.mode=none"), (context, loader) -> {
            assertFalse(has(context, "colocatedAutoConfigurationMarker"));
            assertEquals(List.of(), loader.requested(OUTSIDE));
        });
        run(List.of("topolith.modules.user.mode=internal", "topolith.modules.payment.mode=none"),
                (context, loader) -> assertTrue(has(context, "colocatedAutoConfigurationMarker")));
    }

    @Test
    void ac_mode_23_a_root_holding_two_module_descriptors_falls_back_to_the_package_rule() throws Exception {
        Path shared = Fixtures.dir(temp, "shaded");
        Fixtures.module(shared, "user", USER, List.of(USER_API));
        Fixtures.module(shared, "payment", PAYMENT, List.of(PAYMENT + ".api"), "user");
        Fixtures.imports(shared, USER + ".UserAutoConfiguration", OUTSIDE + ".ColocatedAutoConfiguration");
        run(Host.class, List.of(shared, hostRoot), List.of("topolith.modules.user.mode=none",
                "topolith.modules.payment.mode=none"), (context, loader) -> {
                    assertFalse(has(context, "userAutoConfigurationMarker"), "owned by package");
                    assertTrue(has(context, "colocatedAutoConfigurationMarker"), "the documented limit");
                });
    }

    private void classOutput(Path root, String className) throws IOException {
        String path = className.replace('.', '/') + ".class";
        try (var in = Fixtures.class.getResourceAsStream("/" + path)) {
            Path target = root.resolve(path);
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.write(target, in.readAllBytes());
        }
    }

    @Test
    void ac_mode_23_split_output_roots_the_class_root_owns_an_entry_listed_in_the_resources_root() throws Exception {
        Path classes = Fixtures.dir(temp, "build-classes");
        Fixtures.module(classes, "user", USER, List.of(USER_API));
        classOutput(classes, OUTSIDE + ".ColocatedAutoConfiguration");
        Path resources = Fixtures.dir(temp, "build-resources");
        Fixtures.imports(resources, OUTSIDE + ".ColocatedAutoConfiguration");
        run(Host.class, List.of(classes, resources, paymentRoot, hostRoot),
                List.of("topolith.modules.user.mode=none", "topolith.modules.payment.mode=none"),
                (context, loader) -> {
                    assertFalse(has(context, "colocatedAutoConfigurationMarker"));
                    assertEquals(List.of(), loader.requested(OUTSIDE));
                });
        run(Host.class, List.of(classes, resources, paymentRoot, hostRoot),
                List.of("topolith.modules.user.mode=internal", "topolith.modules.payment.mode=none"),
                (context, loader) -> assertTrue(has(context, "colocatedAutoConfigurationMarker")));
    }

    @Test
    void ac_mode_23_an_entry_whose_file_root_and_class_root_name_different_modules_is_owned_by_neither()
            throws Exception {
        Path classes = Fixtures.dir(temp, "build-classes");
        Fixtures.module(classes, "user", USER, List.of(USER_API));
        classOutput(classes, OUTSIDE + ".ColocatedAutoConfiguration");
        Path resources = Fixtures.dir(temp, "build-resources");
        Fixtures.module(resources, "payment", PAYMENT, List.of(PAYMENT + ".api"), "user");
        Fixtures.imports(resources, OUTSIDE + ".ColocatedAutoConfiguration");
        run(Host.class, List.of(classes, resources, hostRoot),
                List.of("topolith.modules.user.mode=none", "topolith.modules.payment.mode=none"),
                (context, loader) -> assertTrue(has(context, "colocatedAutoConfigurationMarker"),
                        "ambiguous ownership is left alone"));
    }

    @Test
    void ac_mode_24_a_host_scan_covering_a_non_internal_modules_package_fails_with_tl0007() throws Exception {
        run(UserScanningHost.class, List.of(userRoot, hostRoot), List.of("topolith.modules.user.mode=none"),
                (context, loader) -> {
                    assertEquals(List.of(TopolithCode.TL0007), codes(context));
                    String message = failure(context).getMessage();
                    assertTrue(message.contains("module 'user' (mode none)"), message);
                });
        run(ParentScanningHost.class, List.of(userRoot, paymentRoot, hostRoot),
                List.of("topolith.modules.user.mode=external", "topolith.modules.payment.mode=none"),
                (context, loader) -> assertEquals(List.of(TopolithCode.TL0007, TopolithCode.TL0007),
                        codes(context)));
    }

    @Test
    void ac_arch_23_a_host_scan_over_internal_modules_fails() throws Exception {
        run(ParentScanningHost.class, List.of(userRoot, paymentRoot, hostRoot),
                List.of("topolith.modules.user.mode=internal", "topolith.modules.payment.mode=internal"),
                (context, loader) -> assertEquals(List.of(TopolithCode.TL0018, TopolithCode.TL0018),
                        codes(context)));
    }
}
