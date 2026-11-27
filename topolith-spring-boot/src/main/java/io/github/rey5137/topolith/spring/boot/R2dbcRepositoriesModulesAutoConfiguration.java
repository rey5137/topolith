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
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.data.r2dbc.repository.config.R2dbcRepositoryConfigurationExtension;
import org.springframework.data.repository.config.RepositoryConfiguration;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;
import org.springframework.data.repository.config.RepositoryConfigurationSource;
import org.springframework.data.util.Streamable;

/** Spring Data R2DBC repositories for internal modules and restored host packages (D-32). */
@AutoConfiguration(afterName =
        "org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcRepositoriesAutoConfiguration")
@ConditionalOnClass(name = {"org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories",
        "org.springframework.data.r2dbc.core.R2dbcEntityOperations"})
@ConditionalOnBean(type = "org.springframework.data.r2dbc.core.R2dbcEntityOperations")
@ConditionalOnBooleanProperty(name = "spring.data.r2dbc.repositories.enabled", matchIfMissing = true)
@Import({R2dbcRepositoriesModulesAutoConfiguration.ModuleRepositoriesRegistrar.class,
        R2dbcRepositoriesModulesAutoConfiguration.HostRepositoriesRegistrar.class})
class R2dbcRepositoriesModulesAutoConfiguration {
    private abstract static class Registrar extends ModuleRepositoriesSupport.Registrar {
        @EnableR2dbcRepositories(excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
                classes = RegisteredRepositories.class))
        private static final class EnableR2dbcRepositoriesConfiguration {
        }

        @Override
        protected Class<? extends Annotation> getAnnotation() {
            return EnableR2dbcRepositories.class;
        }

        @Override
        protected Class<?> getConfiguration() {
            return EnableR2dbcRepositoriesConfiguration.class;
        }

        @Override
        protected RepositoryConfigurationExtension getRepositoryConfigurationExtension() {
            return new StrictR2dbcRepositoryConfigurationExtension();
        }

        @Override
        protected String factoryBeanType() {
            return "org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactoryBean";
        }

        @Override
        protected String ownConnectionType() {
            return "io.r2dbc.spi.ConnectionFactory";
        }

        @Override
        protected String bootstrapModeProperty() {
            return "spring.data.r2dbc.repositories.bootstrap-mode";
        }
    }

    static final class StrictR2dbcRepositoryConfigurationExtension extends R2dbcRepositoryConfigurationExtension {
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
            return "org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactoryBean";
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
