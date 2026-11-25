package io.github.rey5137.topolith.spring.boot;

import java.nio.file.Path;
import java.util.List;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

class TopolithBackoffCheckTest {
    @TempDir Path root;

    @AutoConfiguration
    static class DefaultAutoConfiguration {
        @Bean @ConditionalOnMissingBean StringBuilder defaultBuilder() { return new StringBuilder(); }
    }

    @AutoConfiguration
    @ConditionalOnMissingBean(StringBuilder.class)
    static class ClassConditionAutoConfiguration { }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    @ImportAutoConfiguration(ClassConditionAutoConfiguration.class)
    static class ClassConditionHost { }

    @AutoConfiguration
    static class DataSourceAutoConfiguration {
        @Bean @ConditionalOnMissingBean javax.sql.DataSource defaultDataSource() {
            return new org.springframework.jdbc.datasource.DriverManagerDataSource();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    @ImportAutoConfiguration(DataSourceAutoConfiguration.class)
    static class PersistenceHost { }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    static class UnconditionalHost { }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    @ImportAutoConfiguration(DefaultAutoConfiguration.class)
    static class Host { }

    @Configuration(proxyBeanMethods = false)
    static class PlainAutoConfiguration {
        @Bean @ConditionalOnMissingBean StringBuilder plainBuilder() { return new StringBuilder(); }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    @ImportAutoConfiguration(PlainAutoConfiguration.class)
    static class PlainHost { }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    @ImportAutoConfiguration(fixture.defaults.ordinary.OwnAutoConfiguration.class)
    static class OwnHost { }

    @Test void ac_arch_28_same_module_auto_configuration_backoff_passes() throws Exception {
        Fixtures.module(root, "user", "fixture.defaults.ordinary", List.of("fixture.defaults.ordinary.api"));
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(OwnHost.class)
                    .withPropertyValues("topolith.modules.user.mode=internal")
                    .run(context -> assertNull(context.getStartupFailure()));
        }
    }

    @Test void ac_arch_28_plain_imported_auto_configuration_backoff_fails() throws Exception {
        Fixtures.module(root, "user", "fixture.defaults.ordinary", List.of("fixture.defaults.ordinary.api"));
        Fixtures.resource(root, "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                PlainAutoConfiguration.class.getName());
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(PlainHost.class)
                    .withPropertyValues("topolith.modules.user.mode=internal").run(context -> {
                        Throwable error = context.getStartupFailure();
                        while (error != null && !(error instanceof TopolithException)) { error = error.getCause(); }
                        assertNotNull(error);
                        assertEquals(TopolithCode.TL0031, ((TopolithException) error).code());
                    });
        }
    }

    static class OnBeanCondition implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext context,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            return false;
        }
    }

    @Test void unparseable_missing_bean_outcome_is_ignored() throws Exception {
        Fixtures.module(root, "user", "fixture.defaults.ordinary", List.of("fixture.defaults.ordinary.api"));
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(UnconditionalHost.class)
                    .withPropertyValues("topolith.modules.user.mode=internal")
                    .withInitializer(context -> org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport
                            .get(context.getBeanFactory()).recordConditionEvaluation(
                                    DefaultAutoConfiguration.class.getName() + "#defaultBuilder", new OnBeanCondition(),
                                    org.springframework.boot.autoconfigure.condition.ConditionOutcome.noMatch(
                                            "@ConditionalOnMissingBean found bean with unknown format")))
                    .run(context -> assertNull(context.getStartupFailure()));
        }
    }

    private void run(String modulePackage, String mode, boolean fails) throws Exception {
        Fixtures.module(root, "user", modulePackage, List.of(modulePackage + ".api"));
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("topolith.modules.user.mode=" + mode).run(context -> {
                        Throwable error = context.getStartupFailure();
                        if (!fails) {
                            assertNull(error);
                            assertTrue(context.containsBean("defaultBuilder"));
                            return;
                        }
                        while (error != null && !(error instanceof TopolithException)) { error = error.getCause(); }
                        assertNotNull(error);
                        assertEquals(TopolithCode.TL0031, ((TopolithException) error).code());
                        assertTrue(error.getMessage().contains("moduleBuilder"));
                        assertTrue(error.getMessage().contains("DefaultAutoConfiguration"));
                    });
        }
    }

    @Test void ac_arch_28_module_default_causes_backoff() throws Exception {
        run("fixture.defaults.ordinary", "internal", true);
    }
    @Test void ac_arch_29_non_default_candidate_does_not_cause_backoff() throws Exception {
        run("fixture.defaults.qualified", "internal", false);
    }
    @Test void ac_arch_30_none_module_does_not_cause_backoff() throws Exception {
        run("fixture.defaults.ordinary", "none", false);
    }
    @Test void ac_arch_31_unconditioned_module_bean_passes() throws Exception {
        Fixtures.module(root, "user", "fixture.defaults.ordinary", List.of("fixture.defaults.ordinary.api"));
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(UnconditionalHost.class)
                    .withPropertyValues("topolith.modules.user.mode=internal").run(context -> {
                        assertNull(context.getStartupFailure());
                        assertTrue(context.containsBean("moduleBuilder"));
                    });
        }
    }

    @Test void ac_arch_32_persistence_backoff_remains_tl0019() throws Exception {
        Fixtures.module(root, "user", "fixture.defaults.persistence", List.of("fixture.defaults.persistence.api"));
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(PersistenceHost.class)
                    .withPropertyValues("topolith.modules.user.mode=internal").run(context -> {
                        Throwable error = context.getStartupFailure();
                        while (error != null && !(error instanceof TopolithException)) { error = error.getCause(); }
                        assertNotNull(error);
                        assertEquals(TopolithCode.TL0019, ((TopolithException) error).code());
                        assertFalse(error.getMessage().contains("TL0031"));
                    });
        }
    }

    @Test void ac_arch_35_class_level_backoff_fails() throws Exception {
        Fixtures.module(root, "user", "fixture.defaults.ordinary", List.of("fixture.defaults.ordinary.api"));
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ClassConditionHost.class)
                    .withPropertyValues("topolith.modules.user.mode=internal").run(context -> {
                        Throwable error = context.getStartupFailure();
                        while (error != null && !(error instanceof TopolithException)) { error = error.getCause(); }
                        assertNotNull(error);
                        assertEquals(TopolithCode.TL0031, ((TopolithException) error).code());
                        assertTrue(error.getMessage().contains("ClassConditionAutoConfiguration"));
                    });
        }
    }

}
