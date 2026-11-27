package io.github.rey5137.topolith.spring.boot;

import java.nio.file.Path;
import java.util.List;

import io.github.rey5137.topolith.spring.boot.Fixtures.RecordingLoader;
import io.github.rey5137.topolith.spring.boot.mybatisfixture.host.MapperHosts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.r2dbc.core.R2dbcEntityOperations;

import static org.junit.jupiter.api.Assertions.*;

class StoreModuleRepositoriesTest {
    @TempDir
    Path temp;

    @Test
    void ac_mode_59_store_repositories_are_registered_with_fully_qualified_names() throws Exception {
        for (String store : List.of("mongo", "r2dbc")) {
            String pkg = "io.github.rey5137.topolith.spring.boot.storefixture." + store;
            String name = pkg + (store.equals("mongo") ? ".MongoItemRepository" : ".R2dbcItemRepository");
            Path root = Fixtures.dir(temp, store);
            Fixtures.module(root, store, pkg, List.of(pkg + ".api"));
            try (RecordingLoader loader = new RecordingLoader(root)) {
                WebApplicationContextRunner runner = new WebApplicationContextRunner(
                        AnnotationConfigServletWebServerApplicationContext::new).withClassLoader(loader)
                        .withUserConfiguration(MapperHosts.Host.class)
                        .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> {
                            for (String beanName : factory.getBeanDefinitionNames()) {
                                String type = factory.getBeanDefinition(beanName).getBeanClassName();
                                if (type != null && type.endsWith("RepositoryFactoryBean")) {
                                    factory.getBeanDefinition(beanName).setLazyInit(true);
                                }
                            }
                        }))
                        .withPropertyValues("server.port=0", "spring.main.lazy-initialization=true",
                                "spring.flyway.enabled=false",
                                "spring.liquibase.enabled=false",
                            "spring.autoconfigure.exclude="
                                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                            "topolith.modules." + store + ".mode=internal");
                runner = store.equals("mongo") ? runner.withBean(MongoOperations.class, () -> null)
                        : runner.withBean(R2dbcEntityOperations.class, () -> null);
                for (String mode : List.of("internal", "loopback")) {
                    runner.withPropertyValues("topolith.modules." + store + ".mode=" + mode).run(context -> {
                        assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                        assertTrue(context.getBeanFactory().containsBeanDefinition(name), name);
                        assertTrue(ModuleBeans.registeredInterfaceNames(context.getBeanFactory(), loader,
                                ModuleBeans.REPOSITORY_FACTORY_BEAN).contains(name));
                    });
                }
            }
        }
    }

    @Test
    void ac_mode_59_module_scan_backs_off_and_host_scan_is_restored() throws Exception {
        for (String store : List.of("mongo", "r2dbc")) {
            String pkg = "io.github.rey5137.topolith.spring.boot.storefixture." + store + "own";
            String host = "io.github.rey5137.topolith.spring.boot.storefixture." + store + "host";
            Path root = Fixtures.dir(temp, store);
            Fixtures.module(root, store, pkg, List.of(pkg + ".api"));
            try (RecordingLoader loader = new RecordingLoader(root)) {
                WebApplicationContextRunner runner = new WebApplicationContextRunner(
                        AnnotationConfigServletWebServerApplicationContext::new).withClassLoader(loader)
                        .withUserConfiguration(Class.forName(host + ".StoreHost"))
                        .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> {
                            for (String beanName : factory.getBeanDefinitionNames()) {
                                String type = factory.getBeanDefinition(beanName).getBeanClassName();
                                if (type != null && type.endsWith("RepositoryFactoryBean")) {
                                    factory.getBeanDefinition(beanName).setLazyInit(true);
                                }
                            }
                        }))
                        .withPropertyValues("spring.flyway.enabled=false", "spring.liquibase.enabled=false",
                                "spring.autoconfigure.exclude="
                                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                                "topolith.modules." + store + ".mode=internal");
                runner = store.equals("mongo") ? runner.withBean(MongoOperations.class, () -> null)
                        : runner.withBean(R2dbcEntityOperations.class, () -> null);
                runner.run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    var registered = ModuleBeans.registeredInterfaceNames(context.getBeanFactory(), loader,
                            ModuleBeans.REPOSITORY_FACTORY_BEAN);
                    assertTrue(registered.contains(pkg + ".ItemRepository"));
                    assertTrue(registered.contains(host + ".HostItemRepository"));
                    assertFalse(context.getBeanFactory().containsBeanDefinition(pkg + ".ItemRepository"));
                });
            }
        }
    }

    @Test
    void ac_mode_60_non_internal_stores_leave_no_definition_or_loaded_class() throws Exception {
        for (String store : List.of("mongo", "r2dbc")) {
            String pkg = "io.github.rey5137.topolith.spring.boot.storefixture." + store;
            Path root = Fixtures.dir(temp, store);
            Fixtures.module(root, store, pkg, List.of(pkg + ".api"));
            for (String mode : List.of("none", "external")) {
                try (RecordingLoader loader = new RecordingLoader(root)) {
                    new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(MapperHosts.Host.class)
                            .withPropertyValues("spring.flyway.enabled=false", "spring.liquibase.enabled=false",
                            "spring.autoconfigure.exclude="
                                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                                    "topolith.modules." + store + ".mode=" + mode,
                                    "topolith.modules." + store + ".remote.url=http://localhost")
                            .run(context -> {
                                assertNull(context.getStartupFailure());
                                assertTrue(loader.requested(pkg).isEmpty());
                                assertTrue(ModuleBeans.registeredInterfaceNames(context.getBeanFactory(), loader,
                                        ModuleBeans.REPOSITORY_FACTORY_BEAN).stream()
                                        .noneMatch(n -> n.startsWith(pkg)));
                            });
                }
            }
        }
    }

    @Test
    void ac_mode_60_without_store_classes_the_configuration_is_not_loaded() throws Exception {
        for (String store : List.of("r2dbc", "mongodb", "mybatis")) {
            String pkg = store.equals("mybatis") ? "org.mybatis.spring.mapper" : "org.springframework.data." + store;
            try (FilteredClassLoader loader = new FilteredClassLoader(pkg)) {
                new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(MapperHosts.Host.class)
                        .withPropertyValues("spring.flyway.enabled=false", "spring.liquibase.enabled=false",
                            "spring.autoconfigure.exclude="
                                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
                        .run(context -> {
                            assertNull(context.getStartupFailure());
                            String configuration = switch (store) {
                                case "r2dbc" -> "R2dbcRepositoriesModulesAutoConfiguration";
                                case "mongodb" -> "MongoRepositoriesModulesAutoConfiguration";
                                default -> "MyBatisRepositoriesModulesAutoConfiguration";
                            };
                            assertTrue(java.util.Arrays.stream(context.getBeanFactory().getBeanDefinitionNames())
                                    .noneMatch(name -> name.contains(configuration)));
                            if (store.equals("mybatis")) {
                                assertFalse(context.getBeanFactory()
                                        .containsBeanDefinition("myBatisModuleMappersRegistrar"));
                            }
                        });
            }
        }
    }
}
