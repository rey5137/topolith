package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.rey5137.topolith.spring.boot.TopologyResolver.Resolution;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.util.ClassUtils;

/**
 * Which module a bean definition came from, and which beans of a type a module contributed (D-32). Types are given by
 * name and loaded only when present, so nothing here depends on an optional library. Shared by every persistence check.
 */
final class ModuleBeans {
    static final String DATA_SOURCE = "javax.sql.DataSource";
    static final String JDBC_CONFIGURATION =
            "org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration";
    static final String ENTITY_MANAGER_FACTORY = "jakarta.persistence.EntityManagerFactory";
    static final String TRANSACTION_MANAGER = "org.springframework.transaction.TransactionManager";
    static final String FLYWAY = "org.flywaydb.core.Flyway";
    static final String LIQUIBASE = "liquibase.integration.spring.SpringLiquibase";
    static final String FLYWAY_INITIALIZER = "org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer";
    /** The factory bean of any Spring Data store. */
    static final String REPOSITORY_FACTORY_BEAN =
            "org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport";
    /** The marker types named by TL0024, as strings: no store library is a dependency (D-36). */
    static final String REPOSITORY = "org.springframework.data.repository.Repository";
    static final String REPOSITORY_DEFINITION = "org.springframework.data.repository.RepositoryDefinition";
    static final String NO_REPOSITORY_BEAN = "org.springframework.data.repository.NoRepositoryBean";
    static final String MAPPER = "org.apache.ibatis.annotations.Mapper";
    static final String MAPPER_FACTORY_BEAN = "org.mybatis.spring.mapper.MapperFactoryBean";
    static final String JPA_REPOSITORY_FACTORY_BEAN =
            "org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean";

    static final String JDBC_REPOSITORY_FACTORY_BEAN =
            "org.springframework.data.jdbc.repository.support.JdbcRepositoryFactoryBean";

    private ModuleBeans() {
    }

    /** The module that owns the definition: its factory method's declaring class, otherwise its bean class. */
    static Optional<String> owner(BeanDefinition definition, Resolution resolution) {
        if (definition instanceof AnnotatedBeanDefinition annotated) {
            String className = annotated.getFactoryMethodMetadata() == null ? definition.getBeanClassName()
                    : annotated.getFactoryMethodMetadata().getDeclaringClassName();
            Optional<String> owner = ownerOfClass(className, resolution);
            if (owner.isPresent()) {
                return owner;
            }
        }
        Optional<String> classOwner = ownerOfClass(definition.getBeanClassName(), resolution);
        if (classOwner.isPresent()) {
            return classOwner;
        }
        if (definition instanceof AbstractBeanDefinition concrete && concrete.getResource() != null) {
            try {
                var url = concrete.getResource().getURL();
                String path = url.getPath();
                Optional<String> packageOwner = resolution.topology().graph().modules().stream()
                        .filter(module -> path.contains("/" + module.packageName().replace('.', '/') + "/"))
                        .map(module -> module.id()).findFirst();
                if (packageOwner.isPresent() || !"jar".equals(url.getProtocol())) {
                    return packageOwner;
                }
                String source = url.toExternalForm();
                return resolution.rootOwners().entrySet().stream().filter(entry -> source.startsWith(entry.getKey()))
                        .map(Map.Entry::getValue).findFirst();
            } catch (IOException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** The module that owns the class: by package, otherwise by the imports file that lists it. */
    static Optional<String> ownerOfClass(String className, Resolution resolution) {
        if (className == null) {
            return Optional.empty();
        }
        boolean api = resolution.topology().graph().modules().stream().flatMap(module -> module.apiPackages().stream())
                .anyMatch(pkg -> className.startsWith(pkg + "."));
        if (api) {
            return Optional.empty();
        }
        Optional<String> owner = resolution.topology().ownerOfClass(className);
        return owner.isPresent() ? owner : Optional.ofNullable(resolution.importOwners().get(className));
    }

    static Class<?> load(String className, ClassLoader loader) {
        try {
            return ClassUtils.forName(className, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** The bean names of the type (by name; none when the class is absent) with the module that contributed each. */
    static Map<String, String> ownedBy(BeanFactory beanFactory, ClassLoader loader, Resolution resolution,
            String typeName) {
        Map<String, String> owned = new TreeMap<>();
        definitionsOfType(beanFactory, loader, typeName)
                .forEach((name, definition) -> owner(definition, resolution).ifPresent(id -> owned.put(name, id)));
        return owned;
    }

    /** The definitions, by name, of the beans of the type (by name; none when the class is absent). */
    private static Map<String, BeanDefinition> definitionsOfType(BeanFactory beanFactory, ClassLoader loader,
            String typeName) {
        Map<String, BeanDefinition> definitions = new LinkedHashMap<>();
        Class<?> type = load(typeName, loader);
        if (type == null || !(beanFactory instanceof ConfigurableListableBeanFactory factory)) {
            return definitions;
        }
        for (String factoryName : factory.getBeanNamesForType(type, true, false)) {
            String name = BeanFactoryUtils.transformedBeanName(factoryName);
            if (factory.containsBeanDefinition(name)) {
                definitions.put(name, factory.getBeanDefinition(name));
            }
        }
        return definitions;
    }

    /** The ids of the modules that contributed at least one bean of the type, sorted. */
    static List<String> contributors(BeanFactory beanFactory, ClassLoader loader, Resolution resolution,
            String typeName) {
        return new ArrayList<>(new TreeSet<>(ownedBy(beanFactory, loader, resolution, typeName).values()));
    }

    /**
     * The default-candidate beans of the type that no module contributed and Topolith did not register itself: the
     * host's own, sorted by name.
     */
    static List<String> hostBeans(BeanFactory beanFactory, ClassLoader loader, Resolution resolution,
            String typeName) {
        Map<String, String> owned = ownedBy(beanFactory, loader, resolution, typeName);
        List<String> names = new ArrayList<>();
        for (String name : defaultCandidates(beanFactory, loader, typeName)) {
            if (!owned.containsKey(name) && !ModuleMigrations.isModuleRunner(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Every runner of the host's own migrations, which a module's runner must wait for: the host's Flyway initializers
     * and Flyway beans and its Liquibase beans. A host clean or {@code dropFirst} then runs before any module.
     */
    static List<String> hostRunners(BeanFactory beanFactory, ClassLoader loader, Resolution resolution) {
        List<String> names = new ArrayList<>();
        for (String type : List.of(FLYWAY_INITIALIZER, FLYWAY, LIQUIBASE)) {
            names.addAll(hostBeans(beanFactory, loader, resolution, type));
        }
        return names;
    }

    /**
     * Why the fallback connection of a module runner is not a single DataSource: "none", or "several" when more than
     * one default candidate exists and not exactly one of them is primary; null when {@code getBean(DataSource.class)}
     * resolves.
     */
    static String dataSourceProblem(BeanFactory beanFactory, ClassLoader loader) {
        List<String> names = new ArrayList<>();
        for (String name : defaultCandidates(beanFactory, loader, DATA_SOURCE)) {
            if (!ModuleMigrations.isModuleRunner(name)) {
                names.add(name);
            }
        }
        if (names.size() <= 1) {
            return names.isEmpty() ? "none" : null;
        }
        long primary = names.stream().filter(name -> beanFactory instanceof ConfigurableListableBeanFactory factory
                && factory.getBeanDefinition(name).isPrimary()).count();
        return primary == 1 ? null : "several";
    }

    /**
     * The ids of the modules that contribute a bean that runs their Flyway scripts: a
     * {@code FlywayMigrationInitializer}, or a {@code Flyway} whose init method is {@code migrate}. A bare
     * {@code Flyway} bean migrates nothing.
     */
    static Set<String> flywayRunners(BeanFactory beanFactory, ClassLoader loader, Resolution resolution) {
        Set<String> ids = new TreeSet<>(contributors(beanFactory, loader, resolution, FLYWAY_INITIALIZER));
        if (beanFactory instanceof ConfigurableListableBeanFactory factory) {
            ownedBy(beanFactory, loader, resolution, FLYWAY).forEach((name, id) -> {
                if ("migrate".equals(factory.getBeanDefinition(name).getInitMethodName())) {
                    ids.add(id);
                }
            });
        }
        return ids;
    }

    /** Each module's own DataSource beans, sorted by name, by module id; a module without one is absent. */
    static Map<String, List<String>> dataSourcesByModule(BeanFactory beanFactory, ClassLoader loader,
            Resolution resolution) {
        Map<String, List<String>> found = new TreeMap<>();
        ownedBy(beanFactory, loader, resolution, DATA_SOURCE)
                .forEach((name, id) -> found.computeIfAbsent(id, key -> new ArrayList<>()).add(name));
        return found;
    }

    /**
     * The modules that declare a migration runner of their own, which then owns their migrations (D-112): a
     * {@code Flyway}, a {@code FlywayMigrationInitializer} or a {@code SpringLiquibase}.
     */
    static Set<String> ownRunners(BeanFactory beanFactory, ClassLoader loader, Resolution resolution) {
        Set<String> ids = new TreeSet<>();
        for (String type : List.of(FLYWAY, FLYWAY_INITIALIZER, LIQUIBASE)) {
            ids.addAll(contributors(beanFactory, loader, resolution, type));
        }
        return ids;
    }

    /** The names of the default-candidate bean definitions of the type, whoever contributed them, sorted. */
    static List<String> defaultCandidates(BeanFactory beanFactory, ClassLoader loader, String typeName) {
        List<String> names = new ArrayList<>();
        Class<?> type = load(typeName, loader);
        if (type == null || !(beanFactory instanceof ConfigurableListableBeanFactory factory)) {
            return names;
        }
        for (String name : new TreeSet<>(List.of(factory.getBeanNamesForType(type, true, false)))) {
            if (factory.containsBeanDefinition(name)
                    && (!(factory.getBeanDefinition(name) instanceof AbstractBeanDefinition definition)
                    || definition.isDefaultCandidate())) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * The repository interface of each repository factory bean definition (any store) with the module that owns it,
     * by interface name.
     */
    static Map<String, String> repositoryInterfaces(BeanFactory beanFactory, ClassLoader loader,
            Resolution resolution, String factoryBeanType) {
        Map<String, String> interfaces = new TreeMap<>();
        for (String interfaceName : repositoryInterfaceNames(beanFactory, loader, factoryBeanType)) {
            interfaces.put(interfaceName, ownerOfClass(interfaceName, resolution).orElse(""));
        }
        return interfaces;
    }

    /** The repository interface names of the definitions of a repository factory bean type: constructor argument 0. */
    static Set<String> repositoryInterfaceNames(BeanFactory beanFactory, ClassLoader loader,
            String factoryBeanType) {
        Set<String> interfaces = new TreeSet<>();
        for (BeanDefinition definition : definitionsOfType(beanFactory, loader, factoryBeanType).values()) {
            ValueHolder argument = definition.getConstructorArgumentValues().getArgumentValue(0, null);
            Object value = argument == null ? null : argument.getValue();
            String interfaceName = value instanceof Class<?> clazz ? clazz.getName()
                    : value == null ? null : value.toString();
            if (interfaceName != null) {
                interfaces.add(interfaceName);
            }
        }
        return interfaces;
    }

    /**
     * Every interface a definition of the factory bean type is registered for, however the registrar recorded it:
     * indexed constructor argument 0 (Spring Data), a generic constructor argument (MyBatis's scanner), or the
     * {@code factoryBeanObjectType} attribute, each as a {@code Class} or a name (TL0024). Unlike
     * {@link #repositoryInterfaceNames} this is for coverage only; TL0007 and the JPA registrars keep argument 0.
     */
    static Set<String> registeredInterfaceNames(BeanFactory beanFactory, ClassLoader loader, String factoryBeanType) {
        Set<String> interfaces = new TreeSet<>(repositoryInterfaceNames(beanFactory, loader, factoryBeanType));
        for (BeanDefinition definition : definitionsOfType(beanFactory, loader, factoryBeanType).values()) {
            for (ValueHolder holder : definition.getConstructorArgumentValues().getGenericArgumentValues()) {
                addName(interfaces, holder.getValue());
            }
            addName(interfaces, definition.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE));
            // a hand-built definition (setMapperInterface, setRepositoryInterface) records it as a property
            for (String property : List.of("repositoryInterface", "mapperInterface")) {
                PropertyValue value = definition.getPropertyValues().getPropertyValue(property);
                addName(interfaces, value == null ? null : value.getValue());
            }
        }
        return interfaces;
    }

    private static void addName(Set<String> names, Object value) {
        if (value instanceof Class<?> clazz) {
            names.add(clazz.getName());
        } else if (value instanceof String text && !text.isBlank()) {
            names.add(text);
        }
    }
}
