package io.github.rey5137.topolith.spring.boot;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.Fixtures.RecordingLoader;
import io.github.rey5137.topolith.spring.boot.mybatisfixture.host.MapperHosts;
import io.github.rey5137.topolith.spring.boot.mybatisfixture.mapper.ItemMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

class MyBatisModuleMappersTest {
    private static final String PACKAGE = "io.github.rey5137.topolith.spring.boot.mybatisfixture.mapper";
    @TempDir
    Path temp;

    private void run(Class<?> host, String mode, BiConsumer<AssertableWebApplicationContext, RecordingLoader> check)
            throws Exception {
        Path root = Fixtures.dir(temp, "mapper");
        Fixtures.module(root, "mapper", PACKAGE, List.of(PACKAGE + ".api"));
        try (RecordingLoader loader = new RecordingLoader(root)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(host)
                    .withPropertyValues("server.port=0", "topolith.modules.mapper.mode=" + mode,
                            mode.equals("loopback") ? "spring.main.banner-mode=off"
                                    : "topolith.modules.mapper.remote.url=http://localhost",
                            "spring.flyway.enabled=false",
                            "spring.liquibase.enabled=false",
                            "spring.autoconfigure.exclude="
                                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
                    .run(context -> check.accept(context, loader));
        }
    }

    @Test
    void ac_mode_58_mapper_is_registered_once_and_xml_is_loaded() throws Exception {
        for (String mode : List.of("internal", "loopback")) {
            for (Class<?> host : List.of(MapperHosts.Host.class, MapperHosts.FactoryBeanHost.class)) {
            run(host, mode, (context, loader) -> {
                assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                assertTrue(context.containsBean(ItemMapper.class.getName()));
                assertEquals(1, context.getBeansOfType(ItemMapper.class).size());
                assertTrue(context.getBean(SqlSessionFactory.class).getConfiguration()
                        .hasStatement(ItemMapper.class.getName() + ".find"));
            });
            }
        }
    }

    @Test
    void ac_mode_58_starter_factory_restores_host_scan_and_preserves_module_name() throws Exception {
        Path root = Fixtures.dir(temp, "starter");
        Fixtures.module(root, "mapper", PACKAGE, List.of(PACKAGE + ".api"));
        try (RecordingLoader loader = new RecordingLoader(root)) {
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withClassLoader(loader).withUserConfiguration(MapperHosts.Starter.class)
                    .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                            org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration.class,
                            MyBatisRepositoriesModulesAutoConfiguration.class))
                    .withPropertyValues("topolith.modules.mapper.mode=internal", "mybatis.lazy-initialization=true",
                            "spring.flyway.enabled=false", "spring.liquibase.enabled=false",
                            "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure."
                                    + "HibernateJpaAutoConfiguration,"
                                    + "org.springframework.boot.data.jdbc.autoconfigure."
                                    + "DataJdbcRepositoriesAutoConfiguration,"
                                    + "org.springframework.boot.data.jpa.autoconfigure."
                                    + "DataJpaRepositoriesAutoConfiguration")
                    .run(context -> {
                        assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                        var factory = context.getBeanFactory();
                        assertTrue(factory.containsBeanDefinition(ItemMapper.class.getName()));
                        assertFalse(factory.containsBeanDefinition("itemMapper"));
                        assertTrue(factory.containsBeanDefinition("hostMapper"));
                        assertTrue(factory.getBeanDefinition(ItemMapper.class.getName()).isLazyInit());
                        assertTrue(factory.getBeanDefinition("hostMapper").isLazyInit());
                    });
        }
    }

    @Test
    void ac_mode_58_explicit_host_scan_is_not_restored_again() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(MapperHosts.OwnHostScan.class)
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration.class,
                        MyBatisRepositoriesModulesAutoConfiguration.class))
                .run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    assertEquals(1, context.getBeansOfType(
                            io.github.rey5137.topolith.spring.boot.mybatisfixture.hostscan.HostMapper.class).size());
                    assertTrue(context.containsBean("hostMapper"));
                });
    }

    @Test
    void ac_mode_58_default_scope_applies_to_host_and_module_mappers() throws Exception {
        Path root = Fixtures.dir(temp, "scope");
        Fixtures.module(root, "mapper", PACKAGE, List.of(PACKAGE + ".api"));
        try (RecordingLoader loader = new RecordingLoader(root)) {
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withClassLoader(loader).withUserConfiguration(MapperHosts.Starter.class)
                    .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                            org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration.class,
                            MyBatisRepositoriesModulesAutoConfiguration.class))
                    .withPropertyValues("topolith.modules.mapper.mode=internal",
                            "mybatis.mapper-default-scope=prototype")
                    .run(context -> {
                        assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                        var factory = context.getBeanFactory();
                        assertEquals("prototype", factory.getBeanDefinition(
                                "scopedTarget." + ItemMapper.class.getName()).getScope());
                        assertEquals("prototype", factory.getBeanDefinition("scopedTarget.hostMapper").getScope());
                    });
        }
    }

    @Test
    void ac_mode_58_multiple_host_factories_fail_with_tl0024() throws Exception {
        run(MapperHosts.Multiple.class, "internal", (context, loader) -> {
            Throwable error = context.getStartupFailure();
            while (error != null && !(error instanceof TopolithException)) {
                error = error.getCause();
            }
            assertNotNull(error);
            assertEquals(TopolithCode.TL0024, ((TopolithException) error).diagnostics().get(0).code());
        });
    }

    @Test
    void ac_mode_58_module_mapper_scan_is_not_registered_again() throws Exception {
        String pkg = "io.github.rey5137.topolith.spring.boot.mybatisfixture.own";
        Path root = Fixtures.dir(temp, "own");
        Fixtures.module(root, "own", pkg, List.of(pkg + ".api"));
        for (Class<?> host : List.of(MapperHosts.Empty.class, MapperHosts.Host.class)) {
            try (RecordingLoader loader = new RecordingLoader(root)) {
                new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                        .withClassLoader(loader).withUserConfiguration(host)
                        .withPropertyValues("topolith.modules.own.mode=internal", "spring.flyway.enabled=false",
                                "spring.liquibase.enabled=false", "spring.autoconfigure.exclude="
                                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
                        .run(context -> {
                            assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                            assertTrue(context.getBeanFactory().containsBeanDefinition("ownedMapper"));
                            assertFalse(context.getBeanFactory().containsBeanDefinition(pkg + ".OwnedMapper"));
                            assertTrue(context.containsBean("ownedFactory"));
                            assertEquals(host == MapperHosts.Host.class, context.containsBean("sqlSessionFactory"));
                            assertNotNull(context.getBean("ownedMapper"));
                        });
            }
        }
    }

    @Test
    void ac_mode_60_non_internal_has_no_mapper_or_loaded_module_class() throws Exception {
        for (String mode : List.of("none", "external")) {
            run(MapperHosts.Host.class, mode, (context, loader) -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.getBeanFactory().containsBeanDefinition(ItemMapper.class.getName()));
                assertFalse(context.getBeanFactory().containsBeanDefinition("itemMapper"));
                assertTrue(loader.requested(PACKAGE).isEmpty());
            });
        }
    }
}
