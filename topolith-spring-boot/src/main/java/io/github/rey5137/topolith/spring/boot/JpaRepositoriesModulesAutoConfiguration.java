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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.JpaRepositoryConfigExtension;
import org.springframework.data.repository.config.RepositoryConfiguration;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;
import org.springframework.data.repository.config.RepositoryConfigurationSource;
import org.springframework.data.util.Streamable;

/** Spring Data JPA repositories for internal modules and restored host packages (D-32). */
@AutoConfiguration(afterName = {"org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@ConditionalOnClass(name = "org.springframework.data.jpa.repository.JpaRepository")
@ConditionalOnBean(type = "javax.sql.DataSource")
@ConditionalOnBooleanProperty(name = "spring.data.jpa.repositories.enabled", matchIfMissing = true)
@Import({JpaRepositoriesModulesAutoConfiguration.ModuleRepositoriesRegistrar.class,
        JpaRepositoriesModulesAutoConfiguration.HostRepositoriesRegistrar.class})
class JpaRepositoriesModulesAutoConfiguration {
    private abstract static class Registrar extends ModuleRepositoriesSupport.Registrar {
        @EnableJpaRepositories(excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
                classes = RegisteredRepositories.class))
        private static final class EnableJpaRepositoriesConfiguration {
        }

        @Override
        protected Class<? extends Annotation> getAnnotation() {
            return EnableJpaRepositories.class;
        }

        @Override
        protected Class<?> getConfiguration() {
            return EnableJpaRepositoriesConfiguration.class;
        }

        @Override
        protected RepositoryConfigurationExtension getRepositoryConfigurationExtension() {
            return new StrictJpaRepositoryConfigExtension();
        }

        @Override
        protected String factoryBeanType() {
            return ModuleBeans.JPA_REPOSITORY_FACTORY_BEAN;
        }

        @Override
        protected String ownConnectionType() {
            return ModuleBeans.ENTITY_MANAGER_FACTORY;
        }

        @Override
        protected String bootstrapModeProperty() {
            return "spring.data.jpa.repositories.bootstrap-mode";
        }
    }

    static final class StrictJpaRepositoryConfigExtension extends JpaRepositoryConfigExtension {
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
            return ModuleBeans.JPA_REPOSITORY_FACTORY_BEAN;
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
