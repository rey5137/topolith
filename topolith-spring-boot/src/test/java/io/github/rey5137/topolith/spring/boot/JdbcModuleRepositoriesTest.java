package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.function.BiConsumer;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.Fixtures.RecordingLoader;
import io.github.rey5137.topolith.spring.boot.jdbcfixture.host.HostJdbcItemRepository;
import io.github.rey5137.topolith.spring.boot.jdbcfixture.host.JdbcHosts.Host;
import io.github.rey5137.topolith.spring.boot.jdbcfixture.host.JdbcHosts.LeakingHost;
import io.github.rey5137.topolith.spring.boot.jdbcfixture.jdbcconfig.UnsafeJdbcConfiguration;
import io.github.rey5137.topolith.spring.boot.jdbcfixture.jdbcitem.JdbcItem;
import io.github.rey5137.topolith.spring.boot.jdbcfixture.jdbcitem.JdbcItemRepository;
import io.github.rey5137.topolith.spring.boot.jdbcfixture.jdbcown.OwnedItemRepository;
import io.github.rey5137.topolith.spring.boot.jdbcfixture.jdbcstandalone.StandaloneApplication;
import io.github.rey5137.topolith.spring.boot.jdbcfixture.jdbcstandalone.StandaloneItemRepository;
import io.github.rey5137.topolith.spring.boot.fixture.ledger.LedgerRepository;
import io.github.rey5137.topolith.spring.boot.fixture.quiet.QuietHost;
import io.github.rey5137.topolith.spring.boot.fixture.quiet.TwoDataSourcesHost;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class JdbcModuleRepositoriesTest {
    private static final String BASE = "io.github.rey5137.topolith.spring.boot.jdbcfixture";

    @TempDir
    Path temp;
    Path item;
    Path own;
    Path config;
    Path standalone;
    Path plain;
    Path ledger;

    @BeforeEach
    void fixtures() throws Exception {
        item = module("jdbcitem");
        own = module("jdbcown");
        config = module("jdbcconfig");
        standalone = module("jdbcstandalone");
        plain = module("jdbcplain");
        ledger = module("ledger");
    }

    private Path module(String id) throws Exception {
        Path root = Fixtures.dir(temp, id);
        String packageName = (id.equals("ledger") ? Fixtures.BASE : BASE) + "." + id;
        Fixtures.module(root, id, packageName, List.of(packageName + ".api"));
        return root;
    }

    private void run(Class<?> host, List<Path> roots, List<String> properties,
            BiConsumer<AssertableApplicationContext, RecordingLoader> assertions) throws Exception {
        try (RecordingLoader loader = new RecordingLoader(roots.toArray(Path[]::new))) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(host)
                    .withPropertyValues("spring.liquibase.enabled=false", "spring.flyway.enabled=false",
                            "spring.jpa.hibernate.ddl-auto=create-drop",
                            "topolith.modules.jdbcitem.remote.url=http://localhost")
                    .withPropertyValues(properties.toArray(String[]::new))
                    .run(context -> assertions.accept(context, loader));
        }
    }

    private static List<TopolithCode> codes(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        assertNotNull(failure, "the context should fail");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TopolithException topolith) {
                return topolith.diagnostics().stream().map(Diagnostic::code).toList();
            }
        }
        return fail("no TopolithException in " + failure);
    }

    @Test
    void ac_mode_32_internal_jdbc_repository_is_named_once_and_works_with_h2() throws Exception {
        run(Host.class, List.of(item), List.of("topolith.modules.jdbcitem.mode=internal"), (context, loader) -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.containsBean(JdbcItemRepository.class.getName()));
            assertEquals(1, context.getBeansOfType(JdbcItemRepository.class).size());
            assertTrue(ModuleBeans.registeredInterfaceNames(context.getBeanFactory(), loader,
                    ModuleBeans.REPOSITORY_FACTORY_BEAN).contains(JdbcItemRepository.class.getName()));
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute("create table \"JDBC_ITEM\" (\"ID\" bigint generated by default as identity, "
                    + "\"NAME\" varchar(255))");
            JdbcItem value = new JdbcItem();
            value.setName("first");
            context.getBean(JdbcItemRepository.class).save(value);
            assertEquals(1L, context.getBean(JdbcItemRepository.class).count());
            assertEquals("first", jdbc.queryForObject("select \"NAME\" from \"JDBC_ITEM\"", String.class));
        });
    }

    @Test
    void ac_mode_33_non_internal_modules_leave_no_repository_or_loaded_class() throws Exception {
        for (String mode : List.of("none", "external")) {
            run(Host.class, List.of(item), List.of("topolith.modules.jdbcitem.mode=" + mode), (context, loader) -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.getBeanFactory().containsBeanDefinition(JdbcItemRepository.class.getName()));
                assertFalse(context.getBeanFactory().containsBeanDefinition("jdbcItemRepository"));
                assertFalse(ModuleBeans.registeredInterfaceNames(context.getBeanFactory(), loader,
                        ModuleBeans.REPOSITORY_FACTORY_BEAN).contains(JdbcItemRepository.class.getName()));
                assertTrue(loader.requested(BASE + ".jdbcitem").isEmpty(),
                        loader.requested(BASE + ".jdbcitem").toString());
            });
        }
    }

    @Test
    void ac_mode_34_host_scan_of_none_repository_fails_with_tl0007() throws Exception {
        run(LeakingHost.class, List.of(item), List.of("topolith.modules.jdbcitem.mode=none"), (context, loader) ->
                assertEquals(List.of(TopolithCode.TL0007), codes(context)));
    }

    @Test
    void ac_mode_35_pattern_a_is_skipped_and_host_repositories_are_restored() throws Exception {
        run(Host.class, List.of(own), List.of("topolith.modules.jdbcown.mode=internal"), (context, loader) -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(OwnedItemRepository.class).size());
            assertFalse(context.containsBean(OwnedItemRepository.class.getName()));
            assertEquals(1, context.getBeansOfType(HostJdbcItemRepository.class).size());
            assertTrue(context.getBeanFactory().getBeanDefinition("hostJdbcItemRepository").getResourceDescription()
                    .contains("JdbcRepositoriesModulesAutoConfiguration"));
        });
    }

    @Test
    void ac_mode_36_standalone_repository_is_registered_once() throws Exception {
        run(StandaloneApplication.class, List.of(standalone), List.of("topolith.standalone=jdbcstandalone"),
                (context, loader) -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(StandaloneItemRepository.class).size());
                });
    }

    @Test
    void ac_mode_37_default_candidate_jdbc_configuration_fails_with_tl0019() throws Exception {
        run(Host.class, List.of(config), List.of("topolith.modules.jdbcconfig.mode=internal"), (context, loader) ->
                assertEquals(List.of(TopolithCode.TL0019), codes(context)));
    }

    @Test
    void ac_mode_37_standalone_jdbc_configuration_is_exempt() throws Exception {
        run(QuietHost.class, List.of(config), List.of("topolith.standalone=jdbcconfig"),
                (context, loader) -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(UnsafeJdbcConfiguration.class).size());
                });
    }

    @Test
    void ac_mode_38_jpa_and_jdbc_repositories_are_registered_for_their_stores() throws Exception {
        run(Host.class, List.of(item, ledger), List.of("topolith.modules.jdbcitem.mode=internal",
                "topolith.modules.ledger.mode=internal"), (context, loader) -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(JdbcItemRepository.class).size());
                    assertEquals(1, context.getBeansOfType(LedgerRepository.class).size());
                    BeanDefinitionRegistry registry = (BeanDefinitionRegistry) context.getBeanFactory();
                    registry.registerBeanDefinition("strictJpaExtension", new RootBeanDefinition(
                            JpaRepositoriesModulesAutoConfiguration.StrictJpaRepositoryConfigExtension.class));
                    registry.registerBeanDefinition("strictJdbcExtension", new RootBeanDefinition(
                            JdbcRepositoriesModulesAutoConfiguration.StrictJdbcRepositoryConfigExtension.class));
                    var extensions = context.getBeansOfType(RepositoryConfigurationExtension.class).values();
                    assertTrue(extensions.stream().anyMatch(extension -> extension instanceof
                            JpaRepositoriesModulesAutoConfiguration.StrictJpaRepositoryConfigExtension),
                            extensions.toString());
                    assertTrue(extensions.stream().anyMatch(extension -> extension instanceof
                            JdbcRepositoriesModulesAutoConfiguration.StrictJdbcRepositoryConfigExtension),
                            extensions.toString());
                    assertEquals("org.springframework.data.jdbc.repository.support.JdbcRepositoryFactoryBean",
                            context.getBeanFactory().getBeanDefinition(JdbcItemRepository.class.getName())
                                    .getBeanClassName());
                    assertEquals("org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean",
                            context.getBeanFactory().getBeanDefinition(LedgerRepository.class.getName())
                                    .getBeanClassName());
                });
    }

    @Test
    void ac_mode_32_without_jpa_an_unannotated_jdbc_aggregate_is_registered() throws Exception {
        FilteredClassLoader withoutJpa = new FilteredClassLoader("org.springframework.data.jpa",
                "org.springframework.data.r2dbc", "org.springframework.data.mongodb") {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return Collections.enumeration(Collections.list(super.getResources(name)).stream()
                        .filter(url -> !url.toString().contains("spring-data-jpa"))
                        .filter(url -> !url.toString().contains("spring-data-r2dbc"))
                        .filter(url -> !url.toString().contains("spring-data-mongodb")).toList());
            }
        };
        try (RecordingLoader loader = new RecordingLoader(withoutJpa, plain)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(QuietHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "spring.flyway.enabled=false",
                            "topolith.modules.jdbcplain.mode=internal")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertTrue(context.containsBean(BASE + ".jdbcplain.PlainItemRepository"));
                    });
        }
    }

    @Test
    void ac_mode_33_without_jdbc_on_the_classpath_the_host_starts() throws Exception {
        ConditionalOnClass guard = JdbcRepositoriesModulesAutoConfiguration.class
                .getAnnotation(ConditionalOnClass.class);
        assertNotNull(guard);
        assertTrue(List.of(guard.name()).contains(
                "org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration"));
        try (RecordingLoader loader = new RecordingLoader(
                new FilteredClassLoader("org.springframework.data.jdbc"), ledger)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(QuietHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "spring.flyway.enabled=false",
                            "spring.jpa.hibernate.ddl-auto=create-drop", "topolith.modules.ledger.mode=internal")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertTrue(context.containsBean(LedgerRepository.class.getName()));
                        assertFalse(context.getBeanFactory().containsBeanDefinition(
                                JdbcRepositoriesModulesAutoConfiguration.class.getName()));
                        for (String name : context.getBeanFactory().getBeanDefinitionNames()) {
                            assertFalse(ModuleBeans.JDBC_REPOSITORY_FACTORY_BEAN.equals(
                                    context.getBeanFactory().getBeanDefinition(name).getBeanClassName()));
                        }
                    });
        }
    }

    @Test
    void ac_mode_32_missing_jdbc_infrastructure_fails_with_tl0024() throws Exception {
        run(TwoDataSourcesHost.class, List.of(item), List.of("topolith.modules.jdbcitem.mode=internal"),
                (context, loader) -> assertEquals(List.of(TopolithCode.TL0024), codes(context)));
    }

    @Test
    void ac_mode_38_unassignable_jdbc_repository_fails_with_tl0024() throws Exception {
        run(Host.class, List.of(plain), List.of("topolith.modules.jdbcplain.mode=internal"), (context, loader) -> {
                    assertEquals(List.of(TopolithCode.TL0024), codes(context));
                    Throwable cause = context.getStartupFailure();
                    while (!(cause instanceof TopolithException)) {
                        cause = cause.getCause();
                    }
                    String action = ((TopolithException) cause).diagnostics().get(0).action();
                    assertTrue(action.contains("@Table"));
                    assertTrue(action.contains("store-specific repository interface"));
                });
    }
}
