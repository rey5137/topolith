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
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.data.mongodb.repository.config.MongoRepositoryConfigurationExtension;
import org.springframework.data.repository.config.RepositoryConfiguration;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;
import org.springframework.data.repository.config.RepositoryConfigurationSource;
import org.springframework.data.util.Streamable;

/** Spring Data MONGO repositories for internal modules and restored host packages (D-32). */
@AutoConfiguration(afterName =
        "org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration")
@ConditionalOnClass(name = {"org.springframework.data.mongodb.repository.config.EnableMongoRepositories",
        "org.springframework.data.mongodb.core.MongoOperations"})
@ConditionalOnBean(type = "org.springframework.data.mongodb.core.MongoOperations")
@ConditionalOnBooleanProperty(name = "spring.data.mongodb.repositories.enabled", matchIfMissing = true)
@Import({MongoRepositoriesModulesAutoConfiguration.ModuleRepositoriesRegistrar.class,
        MongoRepositoriesModulesAutoConfiguration.HostRepositoriesRegistrar.class})
class MongoRepositoriesModulesAutoConfiguration {
    private abstract static class Registrar extends ModuleRepositoriesSupport.Registrar {
        @EnableMongoRepositories(excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
                classes = RegisteredRepositories.class))
        private static final class EnableMongoRepositoriesConfiguration {
        }

        @Override
        protected Class<? extends Annotation> getAnnotation() {
            return EnableMongoRepositories.class;
        }

        @Override
        protected Class<?> getConfiguration() {
            return EnableMongoRepositoriesConfiguration.class;
        }

        @Override
        protected RepositoryConfigurationExtension getRepositoryConfigurationExtension() {
            return new StrictMongoRepositoryConfigurationExtension();
        }

        @Override
        protected String factoryBeanType() {
            return "org.springframework.data.mongodb.repository.support.MongoRepositoryFactoryBean";
        }

        @Override
        protected String ownConnectionType() {
            return "org.springframework.data.mongodb.MongoDatabaseFactory";
        }

        @Override
        protected String bootstrapModeProperty() {
            return "spring.data.mongodb.repositories.bootstrap-mode";
        }
    }

    static final class StrictMongoRepositoryConfigurationExtension extends MongoRepositoryConfigurationExtension {
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
            return "org.springframework.data.mongodb.repository.support.MongoRepositoryFactoryBean";
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
