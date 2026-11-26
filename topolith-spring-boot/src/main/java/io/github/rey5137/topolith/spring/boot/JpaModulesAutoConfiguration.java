package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.TopologyResolver.Resolution;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.ManagedType;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.ManagedClassNameFilter;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;

/**
 * Shared JPA for the internal modules (D-32). It declares the {@code persistenceManagedTypes} bean ahead of Boot's,
 * from the host's packages chosen as Boot chooses them plus the package of each internal module that uses the host's
 * persistence, and it checks the result once the entity-manager factories exist: TL0018 for entity-scan packages that
 * overlap a module, TL0007 for an entity of a non-internal module, TL0021 for an entity of an internal module that
 * is managed by no entity-manager factory or by several. Only this class touches the JPA types.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass(name = {"org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes",
        "jakarta.persistence.EntityManager", "org.springframework.boot.persistence.autoconfigure.EntityScanPackages"})
class JpaModulesAutoConfiguration {

    @Bean("persistenceManagedTypes")
    @ConditionalOnMissingBean({PersistenceManagedTypes.class, LocalContainerEntityManagerFactoryBean.class,
            EntityManagerFactory.class})
    static PersistenceManagedTypes persistenceManagedTypes(BeanFactory beanFactory, ResourceLoader resourceLoader,
            Environment environment, ObjectProvider<ManagedClassNameFilter> filters) {
        ClassLoader loader = beanFactory instanceof ConfigurableListableBeanFactory factory
                ? factory.getBeanClassLoader() : null;
        Resolution resolution = TopologyResolver.resolve(beanFactory, environment, loader);
        Set<String> apiPackages = resolution.topology().apiPackages();
        List<ManagedClassNameFilter> userFilters = filters.orderedStream().toList();
        ManagedClassNameFilter filter = className -> !apiPackages.contains(packageOf(className))
                && userFilters.stream().allMatch(user -> user.matches(className));
        List<String> packages = new ArrayList<>(hostPackages(beanFactory));
        packages.addAll(sharedModulePackages(beanFactory, loader, resolution));
        return new PersistenceManagedTypesScanner(resourceLoader, filter)
                .scan(outermost(packages).toArray(String[]::new));
    }

    /** The host's entity-scan packages if it has any, otherwise its auto-configuration packages, as Boot does. */
    private static List<String> hostPackages(BeanFactory beanFactory) {
        List<String> scan = EntityScanPackages.get(beanFactory).getPackageNames();
        if (!scan.isEmpty()) {
            return scan;
        }
        return AutoConfigurationPackages.has(beanFactory) ? AutoConfigurationPackages.get(beanFactory) : List.of();
    }

    /** The package of each internal module that does not contribute an entity-manager factory, sorted by id. */
    private static List<String> sharedModulePackages(BeanFactory beanFactory, ClassLoader loader,
            Resolution resolution) {
        Topology topology = resolution.topology();
        List<String> own = ModuleBeans.contributors(beanFactory, loader, resolution,
                ModuleBeans.ENTITY_MANAGER_FACTORY);
        List<String> packages = new ArrayList<>();
        for (String id : new TreeSet<>(topology.internal())) {
            if (!own.contains(id)) {
                packages.add(topology.modulePackages().get(id));
            }
        }
        return packages;
    }

    /** The packages without any that another one contains, so the standalone module is scanned once (D-32). */
    private static List<String> outermost(List<String> packages) {
        List<String> kept = new ArrayList<>();
        for (String candidate : new TreeSet<>(packages)) {
            if (kept.stream().noneMatch(outer -> candidate.startsWith(outer + "."))) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    private static String packageOf(String className) {
        return className.substring(0, Math.max(className.lastIndexOf('.'), 0));
    }

    @Bean
    static BeanFactoryPostProcessor topolithEntityScanCheck(Environment environment) {
        return new EntityScanCheck(environment);
    }

    @Bean
    static EntityCheck topolithEntityCheck(Environment environment) {
        return new EntityCheck(environment);
    }

    /**
     * TL0018 for {@code @EntityScan} packages, which Boot reads in place of the auto-configuration packages, and
     * TL0021 for an internal module's entities when no entity-manager factory is defined at all.
     */
    private static final class EntityScanCheck implements BeanFactoryPostProcessor {
        private final Environment environment;

        EntityScanCheck(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) throws BeansException {
            Topology topology = TopologyResolver.resolve(factory, environment, factory.getBeanClassLoader())
                    .topology();
            List<Diagnostic> found = TopolithPersistenceCheck.overlaps("entity-scan",
                    EntityScanPackages.get(factory).getPackageNames(), topology,
                    TopologyResolver.standalone(environment));
            if (found.isEmpty()
                    && factory.getBeanNamesForType(EntityManagerFactory.class, true, false).length == 0) {
                // no factory is defined at all: nothing can manage an internal module's entities, and none is created
                for (String id : new TreeSet<>(topology.internal())) {
                    for (String className : entities(topology, id, factory.getBeanClassLoader(), environment)) {
                        found.add(unmanaged(className, id, 0));
                    }
                }
            }
            if (!found.isEmpty()) {
                throw new TopolithException(found);
            }
        }
    }

    /**
     * The checks that need every entity-manager factory: TL0007 and TL0021. They run before the first repository is
     * created, because a repository for an entity that no factory manages would otherwise fail first with Hibernate's
     * own error, and once all singletons exist for a module without repositories.
     */
    static final class EntityCheck implements SmartInitializingSingleton, InstantiationAwareBeanPostProcessor,
            BeanFactoryAware, BeanClassLoaderAware {
        private final AtomicBoolean checked = new AtomicBoolean();
        private BeanFactory beanFactory;
        private ClassLoader classLoader;
        private final Environment environment;

        EntityCheck(Environment environment) {
            this.environment = environment;
        }

        @Override
        public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) throws BeansException {
            for (Class<?> type = beanClass; type != null; type = type.getSuperclass()) {
                if (type.getName().equals(ModuleBeans.REPOSITORY_FACTORY_BEAN)) {
                    check();
                    break;
                }
            }
            return null;
        }

        @Override
        public void afterSingletonsInstantiated() {
            check();
        }

        private void check() {
            if (checked.compareAndSet(false, true)) {
                run();
            }
        }

        private void run() {
            Resolution resolution = TopologyResolver.resolve(beanFactory, environment, classLoader);
            Topology topology = resolution.topology();
            if (topology.modulePackages().isEmpty() || !(beanFactory instanceof ListableBeanFactory factory)) {
                return;
            }
            Map<String, EntityManagerFactory> factories = new TreeMap<>(factory
                    .getBeansOfType(EntityManagerFactory.class));
            Map<String, Integer> managedBy = new TreeMap<>();
            Map<String, List<String>> foreign = new TreeMap<>();
            for (EntityManagerFactory emf : factories.values()) {
                for (ManagedType<?> type : emf.getMetamodel().getManagedTypes()) {
                    String className = type.getJavaType().getName();
                    managedBy.merge(className, 1, Integer::sum);
                    String owner = ModuleBeans.ownerOfClass(className, resolution).orElse(null);
                    if (owner != null && !topology.internal().contains(owner)) {
                        foreign.computeIfAbsent(owner, key -> new ArrayList<>()).add(className);
                    }
                }
            }
            List<Diagnostic> found = new ArrayList<>();
            foreign.forEach((owner, classes) -> {
                classes.sort(null);
                found.add(new Diagnostic(TopolithCode.TL0007, "an entity-manager factory manages "
                        + classes.size() + " type(s) of module '" + owner + "' (mode " + topology.mode(owner).id()
                        + "), such as " + classes.get(0) + ".", "Remove the module's package from the host's "
                        + "managed types, or set topolith.modules." + owner + ".mode to internal."));
            });
            for (String id : new TreeSet<>(topology.internal())) {
                for (String className : entities(topology, id, classLoader, environment)) {
                    int count = managedBy.getOrDefault(className, 0);
                    if (count != 1) {
                        found.add(unmanaged(className, id, count));
                    }
                }
            }
            if (!found.isEmpty()) {
                throw new TopolithException(found);
            }
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

    /** TL0021 for an entity that {@code count} entity-manager factories manage. */
    private static Diagnostic unmanaged(String className, String id, int count) {
        return new Diagnostic(TopolithCode.TL0021, "the entity " + className + " of module '" + id + "' is managed by "
                + (count == 0 ? "no" : count) + " entity-manager factor" + (count == 1 ? "y" : "ies") + ".",
                "Let exactly one entity-manager factory manage it: the host's, from its entity scan, or the module's "
                + "own.");
    }

    /** The {@code @Entity} classes under the module's package, less the api packages, sorted. */
    private static List<String> entities(Topology topology, String id, ClassLoader classLoader,
            Environment environment) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(
                false, environment) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.setResourceLoader(new DefaultResourceLoader(classLoader));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class, false));
        Set<String> names = new TreeSet<>();
        scanner.findCandidateComponents(topology.modulePackages().get(id)).forEach(candidate -> {
            String className = candidate.getBeanClassName();
            if (!topology.apiPackages().contains(packageOf(className))) {
                names.add(className);
            }
        });
        return new ArrayList<>(names);
    }
}
