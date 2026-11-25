package io.github.rey5137.topolith.spring.boot;

import java.lang.annotation.Annotation;
import java.util.Collection;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.jdbc.repository.config.JdbcRepositoryConfigExtension;
import org.springframework.data.repository.config.RepositoryConfiguration;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;
import org.springframework.data.repository.config.RepositoryConfigurationSource;
import org.springframework.data.util.Streamable;

/** Spring Data JDBC repositories for internal modules and restored host packages (D-32). */
@AutoConfiguration(afterName = "org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration")
@ConditionalOnClass(name = {"org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
        "org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration"})
@ConditionalOnBean(type = {"org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
        "org.springframework.transaction.PlatformTransactionManager",
        "org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration"})
@ConditionalOnBooleanProperty(name = "spring.data.jdbc.repositories.enabled", matchIfMissing = true)
@Import({JdbcRepositoriesModulesAutoConfiguration.ModuleRepositoriesRegistrar.class,
        JdbcRepositoriesModulesAutoConfiguration.HostRepositoriesRegistrar.class})
class JdbcRepositoriesModulesAutoConfiguration {
    private abstract static class Registrar extends ModuleRepositoriesSupport.Registrar {
        @EnableJdbcRepositories(excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
                classes = RegisteredRepositories.class))
        private static final class EnableJdbcRepositoriesConfiguration {
        }

        @Override
        protected Class<? extends Annotation> getAnnotation() {
            return EnableJdbcRepositories.class;
        }

        @Override
        protected Class<?> getConfiguration() {
            return EnableJdbcRepositoriesConfiguration.class;
        }

        @Override
        protected RepositoryConfigurationExtension getRepositoryConfigurationExtension() {
            return new StrictJdbcRepositoryConfigExtension();
        }

        @Override
        protected String factoryBeanType() {
            return ModuleBeans.JDBC_REPOSITORY_FACTORY_BEAN;
        }

        @Override
        protected String ownConnectionType() {
            return ModuleBeans.DATA_SOURCE;
        }

        @Override
        protected String bootstrapModeProperty() {
            return "spring.data.jdbc.repositories.bootstrap-mode";
        }
    }

    static final class StrictJdbcRepositoryConfigExtension extends JdbcRepositoryConfigExtension {
        @Override
        public <T extends RepositoryConfigurationSource> Collection<RepositoryConfiguration<T>>
                getRepositoryConfigurations(T source, ResourceLoader loader, boolean strict) {
            return ModuleRepositoriesSupport.strictCandidates(super.getRepositoryConfigurations(source, loader, strict),
                    getConfigurationInspectionClassLoader(loader), strict, this::isStrictRepositoryCandidate);
        }
    }

    public static final class RegisteredRepositories extends ModuleRepositoriesSupport.RegisteredRepositories {
        @Override
        protected String factoryBeanType() {
            return ModuleBeans.JDBC_REPOSITORY_FACTORY_BEAN;
        }
    }

    static final class ModuleRepositoriesRegistrar extends Registrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry,
                BeanNameGenerator importBeanNameGenerator) {
            registerModules(metadata, registry);
        }

        @Override
        protected Streamable<String> getBasePackages() {
            return modulePackages();
        }
    }

    static final class HostRepositoriesRegistrar extends Registrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry,
                BeanNameGenerator importBeanNameGenerator) {
            registerHost(metadata, registry, importBeanNameGenerator);
        }
    }
}
