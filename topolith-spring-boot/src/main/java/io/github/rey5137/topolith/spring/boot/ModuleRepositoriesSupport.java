package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import io.github.rey5137.topolith.spring.boot.TopologyResolver.Resolution;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.data.AbstractRepositoryConfigurationSourceSupport;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.data.repository.config.RepositoryConfiguration;
import org.springframework.data.repository.config.RepositoryConfigurationSource;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.AbstractRepositoryMetadata;
import org.springframework.data.util.Streamable;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/** Shared per-module and host repository registration for Spring Data stores. */
final class ModuleRepositoriesSupport {
    private ModuleRepositoriesSupport() {
    }

    // Our duplicate exclude filter is not a store selection; preserve Spring Data's strict multi-store check.
    static <T extends RepositoryConfigurationSource> Collection<RepositoryConfiguration<T>> strictCandidates(
            Collection<RepositoryConfiguration<T>> candidates, ClassLoader loader, boolean strict,
            Predicate<RepositoryMetadata> matchesStore) {
        if (!strict) {
            return candidates;
        }
        return candidates.stream().filter(candidate -> {
            try {
                Class<?> type = ClassUtils.forName(candidate.getRepositoryInterface(), loader);
                return matchesStore.test(AbstractRepositoryMetadata.getMetadata(type));
            } catch (ClassNotFoundException | LinkageError ex) {
                return true;
            }
        }).toList();
    }

    abstract static class Registrar extends AbstractRepositoryConfigurationSourceSupport {
        private BootstrapMode bootstrapMode;
        private BeanFactory beanFactory;
        private Environment environment;

        protected abstract String factoryBeanType();

        protected abstract String ownConnectionType();

        protected abstract String bootstrapModeProperty();

        private Resolution resolution() {
            return TopologyResolver.resolve(beanFactory, environment, loader());
        }

        private ClassLoader loader() {
            return beanFactory instanceof ConfigurableListableBeanFactory factory ? factory.getBeanClassLoader() : null;
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
            String mode = environment.getProperty(bootstrapModeProperty());
            if (StringUtils.hasText(mode)) {
                this.bootstrapMode = BootstrapMode.valueOf(mode.toUpperCase(Locale.ENGLISH));
            }
        }

        @Override
        protected BootstrapMode getBootstrapMode() {
            return this.bootstrapMode != null ? this.bootstrapMode : super.getBootstrapMode();
        }

        protected final void registerModules(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            if (!getBasePackages().isEmpty()) {
                super.registerBeanDefinitions(metadata, registry, new FullyQualifiedAnnotationBeanNameGenerator());
            }
        }

        protected final Streamable<String> modulePackages() {
            Resolution resolution = resolution();
            List<String> ownConnections = ModuleBeans.contributors(beanFactory, loader(), resolution,
                    ownConnectionType());
            List<String> hostPackages = AutoConfigurationPackages.has(beanFactory)
                    ? AutoConfigurationPackages.get(beanFactory) : List.of();
            Set<String> own = new TreeSet<>();
            ModuleBeans.repositoryInterfaces(beanFactory, loader(), resolution, factoryBeanType())
                    .forEach((name, owner) -> {
                        if (hostPackages.stream().noneMatch(host -> name.startsWith(host + "."))) {
                            own.add(owner);
                        }
                    });
            List<String> packages = new ArrayList<>();
            for (String id : new TreeSet<>(resolution.topology().internal())) {
                if (!ownConnections.contains(id) && !own.contains(id)) {
                    packages.add(resolution.topology().modulePackages().get(id));
                }
            }
            return Streamable.of(packages);
        }

        protected final void registerHost(AnnotationMetadata metadata, BeanDefinitionRegistry registry,
                BeanNameGenerator importBeanNameGenerator) {
            Map<String, String> registered = ModuleBeans.repositoryInterfaces(beanFactory, loader(), resolution(),
                    factoryBeanType());
            if (!registered.containsValue("")) {
                super.registerBeanDefinitions(metadata, registry, importBeanNameGenerator);
            }
        }
    }

    /** Excludes repository interfaces that already have a definition, whatever its bean name. */
    abstract static class RegisteredRepositories implements TypeFilter, BeanFactoryAware, BeanClassLoaderAware {
        private BeanFactory beanFactory;
        private ClassLoader classLoader;
        private Set<String> registered;

        protected abstract String factoryBeanType();

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory readers) {
            if (registered == null) {
                registered = ModuleBeans.repositoryInterfaceNames(beanFactory, classLoader, factoryBeanType());
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
}
