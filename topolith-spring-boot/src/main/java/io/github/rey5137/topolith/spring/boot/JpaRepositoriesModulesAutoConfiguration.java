package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.rey5137.topolith.spring.boot.TopologyResolver.Resolution;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.data.AbstractRepositoryConfigurationSourceSupport;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.StringUtils;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.JpaRepositoryConfigExtension;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;
import org.springframework.data.util.Streamable;

/**
 * Spring Data JPA repositories for the internal modules (D-32), ordered after Boot's repository auto-configuration so
 * that Boot's {@code @ConditionalOnMissingBean} never sees them. The first registrar registers each internal module
 * that uses the host's persistence, under fully qualified bean names. The second restores Boot's scan of the host's
 * packages when only module registrations made Boot back off. Only this file touches Spring Data.
 */
@AutoConfiguration(afterName = {"org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@ConditionalOnClass(name = "org.springframework.data.jpa.repository.JpaRepository")
@ConditionalOnBean(type = "javax.sql.DataSource")
@ConditionalOnBooleanProperty(name = "spring.data.jpa.repositories.enabled", matchIfMissing = true)
@Import({JpaRepositoriesModulesAutoConfiguration.ModuleRepositoriesRegistrar.class,
        JpaRepositoriesModulesAutoConfiguration.HostRepositoriesRegistrar.class})
class JpaRepositoriesModulesAutoConfiguration {

    private abstract static class Registrar extends AbstractRepositoryConfigurationSourceSupport {
        /**
         * The annotation that Spring Data reads its defaults from; nested here so Spring never parses it itself. It
         * leaves out every repository interface that already has a definition, so a standalone module that the host's
         * scan covers is registered once (D-32).
         */
        @EnableJpaRepositories(excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
                classes = RegisteredRepositories.class))
        private static final class EnableJpaRepositoriesConfiguration {
        }

        private BootstrapMode bootstrapMode;

        private BeanFactory beanFactory;
        private Environment environment;

        @Override
        protected Class<? extends java.lang.annotation.Annotation> getAnnotation() {
            return EnableJpaRepositories.class;
        }

        @Override
        protected Class<?> getConfiguration() {
            return EnableJpaRepositoriesConfiguration.class;
        }

        @Override
        protected RepositoryConfigurationExtension getRepositoryConfigurationExtension() {
            return new JpaRepositoryConfigExtension();
        }

        Resolution resolution() {
            ClassLoader loader = beanFactory instanceof ConfigurableListableBeanFactory factory
                    ? factory.getBeanClassLoader() : null;
            return TopologyResolver.resolve(beanFactory, environment, loader);
        }

        ClassLoader loader() {
            return beanFactory instanceof ConfigurableListableBeanFactory factory ? factory.getBeanClassLoader()
                    : null;
        }

        BeanFactory beanFactory() {
            return beanFactory;
        }

        @Override
        public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
            super.setBeanFactory(beanFactory);
            this.beanFactory = beanFactory;
        }

        @Override
        public void setEnvironment(Environment environment) {
            super.setEnvironment(environment);
            this.environment = environment;
            String mode = environment.getProperty("spring.data.jpa.repositories.bootstrap-mode");
            if (StringUtils.hasText(mode)) {
                this.bootstrapMode = BootstrapMode.valueOf(mode.toUpperCase(Locale.ENGLISH));
            }
        }

        /** The mode Boot's own registrar uses, so module and restored host repositories honour the host's setting. */
        @Override
        protected BootstrapMode getBootstrapMode() {
            return this.bootstrapMode != null ? this.bootstrapMode : super.getBootstrapMode();
        }
    }

    /** Excludes the repository interfaces that already have a JPA repository definition, whatever its bean name. */
    public static final class RegisteredRepositories implements TypeFilter, BeanFactoryAware, BeanClassLoaderAware {
        private BeanFactory beanFactory;
        private ClassLoader classLoader;
        private Set<String> registered;

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory readers) {
            if (registered == null) {
                registered = ModuleBeans.repositoryInterfaceNames(beanFactory, classLoader,
                        ModuleBeans.JPA_REPOSITORY_FACTORY_BEAN);
            }
            return registered.contains(reader.getClassMetadata().getClassName());
        }

        @Override
        public void setBeanFactory(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void setBeanClassLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }
    }

    /** The internal modules that use the host's persistence, sorted by id, under fully qualified bean names. */
    static final class ModuleRepositoriesRegistrar extends Registrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry,
                BeanNameGenerator importBeanNameGenerator) {
            if (!getBasePackages().isEmpty()) {
                super.registerBeanDefinitions(metadata, registry, new FullyQualifiedAnnotationBeanNameGenerator());
            }
        }

        @Override
        protected Streamable<String> getBasePackages() {
            Resolution resolution = resolution();
            List<String> ownEntityManagers = ModuleBeans.contributors(beanFactory(), loader(), resolution,
                    ModuleBeans.ENTITY_MANAGER_FACTORY);
            List<String> hostPackages = AutoConfigurationPackages.has(beanFactory())
                    ? AutoConfigurationPackages.get(beanFactory()) : List.of();
            Set<String> own = new TreeSet<>();
            ModuleBeans.repositoryInterfaces(beanFactory(), loader(), resolution,
                    ModuleBeans.JPA_REPOSITORY_FACTORY_BEAN).forEach((name, owner) -> {
                        if (hostPackages.stream().noneMatch(host -> name.startsWith(host + "."))) {
                            own.add(owner);
                        }
                    });
            List<String> packages = new ArrayList<>();
            for (String id : new TreeSet<>(resolution.topology().internal())) {
                if (!ownEntityManagers.contains(id) && !own.contains(id)) {
                    packages.add(resolution.topology().modulePackages().get(id));
                }
            }
            return Streamable.of(packages);
        }
    }

    /** Boot's scan of the host's packages, when no definition for a host repository exists yet. */
    static final class HostRepositoriesRegistrar extends Registrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry,
                BeanNameGenerator importBeanNameGenerator) {
            Map<String, String> registered = ModuleBeans.repositoryInterfaces(beanFactory(), loader(), resolution(),
                    ModuleBeans.JPA_REPOSITORY_FACTORY_BEAN);
            if (!registered.containsValue("")) {
                super.registerBeanDefinitions(metadata, registry, importBeanNameGenerator);
            }
        }
    }
}
