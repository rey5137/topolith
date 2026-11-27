package io.github.rey5137.topolith.spring.boot;

import java.lang.annotation.Annotation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.ModuleMigrations.Migrations;
import io.github.rey5137.topolith.spring.boot.ModuleMigrations.Tool;
import io.github.rey5137.topolith.spring.boot.TopologyResolver.Resolution;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.ClassUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.env.Environment;

/**
 * Persistence checks that need no persistence library on the class path (D-32). They run once every definition is
 * registered and name the optional types only as strings: a host package that overlaps a module package (TL0018), an
 * internal module's own persistence bean that is a default candidate (TL0019), a repository of a non-internal module
 * (TL0007), and module migrations that would not run exactly once (TL0020).
 */
final class TopolithPersistenceCheck implements BeanFactoryPostProcessor, EnvironmentAware {
    private static final List<String> OWN_TYPES = List.of(ModuleBeans.DATA_SOURCE, ModuleBeans.ENTITY_MANAGER_FACTORY,
            ModuleBeans.TRANSACTION_MANAGER, ModuleBeans.FLYWAY, ModuleBeans.FLYWAY_INITIALIZER,
            ModuleBeans.LIQUIBASE, ModuleBeans.JDBC_CONFIGURATION);

    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    private static final Pattern TYPED = Pattern.compile(
            "found beans? of type '[^']+' (.*?)(?= and found bean|$)");

    private Environment environment;

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) throws BeansException {
        ClassLoader loader = factory.getBeanClassLoader();
        Resolution resolution = TopologyResolver.resolve(factory, environment, loader);
        Topology topology = resolution.topology();
        if (topology.modulePackages().isEmpty()) {
            return;
        }
        List<Diagnostic> found = new ArrayList<>();
        if (AutoConfigurationPackages.has(factory)) {
            found.addAll(overlaps("auto-configuration", AutoConfigurationPackages.get(factory), topology,
                    TopologyResolver.standalone(environment)));
            if (!found.isEmpty()) {
                throw new TopolithException(found);
            }
        }
        found.addAll(MapperLocationsCheck.check(environment, loader, topology,
                TopologyResolver.standalone(environment)));
        found.addAll(resourceRegistrations(loader, resolution));
        found.addAll(nonInternalBeans(factory, resolution));
        found.addAll(defaultCandidates(factory, loader, resolution));
        found.addAll(backedOffDefaults(factory, loader, resolution));
        found.addAll(repositories(factory, loader, resolution));
        found.addAll(unregistered(factory, loader, resolution));
        found.addAll(migrations(factory, loader, resolution));
        if (!found.isEmpty()) {
            throw new TopolithException(found);
        }
    }

    /**
     * TL0018: a host package equal to, above or below a module's package, whatever the mode (D-32). The standalone
     * module is exempt: there the module is the application, and Topolith adds nothing the host's scan covers.
     */
    static List<Diagnostic> overlaps(String source, Collection<String> hostPackages, Topology topology,
            String standalone) {
        List<Diagnostic> found = new ArrayList<>();
        for (String hostPackage : new TreeSet<>(hostPackages)) {
            topology.modulePackages().forEach((id, modulePackage) -> {
                if (id.equals(standalone)) {
                    return;
                }
                if (hostPackage.equals(modulePackage) || hostPackage.startsWith(modulePackage + ".")
                        || modulePackage.startsWith(hostPackage + ".") || hostPackage.isEmpty()) {
                    found.add(new Diagnostic(TopolithCode.TL0018, "the host's " + source + " package '" + hostPackage
                            + "' overlaps the package '" + modulePackage + "' of module '" + id + "'.",
                            "Move the host's @SpringBootApplication (or @EntityScan) class to a package that holds "
                            + "no module, for example com.acme.app."));
                }
            });
        }
        return found;
    }

    /** TL0007: management imports can activate in a child context outside this factory. */
    private static List<Diagnostic> resourceRegistrations(ClassLoader loader, Resolution resolution) {
        if (!ClassUtils.isPresent(
                "org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration",
                loader)) {
            return List.of();
        }
        List<Diagnostic> found = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
        try {
            for (Resource resource : resolver.getResources(
                    "classpath*:META-INF/spring/*ManagementContextConfiguration.imports")) {
                String url = resource.getURL().toString();
                String content;
                try (var in = resource.getInputStream()) {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                for (String line : content.split("\\R")) {
                    String className = line.split("#", 2)[0].trim();
                    String id = resolution.topology().ownerOfClass(className).orElse(null);
                    if (id != null && !resolution.topology().internal().contains(id)) {
                        found.add(new Diagnostic(TopolithCode.TL0007, "module '" + id + "' lists "
                                + className + " in " + url + ".",
                                "Remove the registration or activate the module."));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read module registration resources", e);
        }
        return found;
    }

    private static String registrationSource(BeanDefinition definition) {
        if (definition.getResourceDescription() != null) {
            return definition.getResourceDescription();
        }
        if (definition.getFactoryMethodName() != null) {
            return (definition.getBeanClassName() != null ? definition.getBeanClassName() : "factory")
                    + "#" + definition.getFactoryMethodName();
        }
        return definition.getBeanClassName() != null ? definition.getBeanClassName() : "an unknown source";
    }

    private static boolean externalClient(String name, BeanDefinition definition, Resolution resolution) {
        if (!(definition instanceof AbstractBeanDefinition bean) || bean.getInstanceSupplier() == null) {
            return false;
        }
        String type = definition.getBeanClassName();
        for (String id : resolution.topology().idsOf(Mode.EXTERNAL)) {
            if (!id.equals(definition.getAttribute("httpServiceGroupName")) || !(id + "#" + type).equals(name)) {
                continue;
            }
            var mappings = resolution.httpInterfaces().getOrDefault(id, Map.of());
            if (resolution.contracts().getOrDefault(id, Set.of()).stream()
                    .anyMatch(contract -> mappings.getOrDefault(contract, contract).equals(type))) {
                return true;
            }
        }
        return false;
    }

    private static List<Diagnostic> nonInternalBeans(ConfigurableListableBeanFactory factory, Resolution resolution) {
        List<Diagnostic> found = new ArrayList<>();
        for (String name : factory.getBeanDefinitionNames()) {
            BeanDefinition definition = factory.getBeanDefinition(name);
            if (externalClient(name, definition, resolution)) {
                continue;
            }
            ModuleBeans.owner(definition, resolution).ifPresent(id -> {
                if (!resolution.topology().internal().contains(id)) {
                    found.add(new Diagnostic(TopolithCode.TL0007, "bean '" + name + "' declared by module '" + id
                            + "' (mode " + resolution.topology().mode(id).id() + ") was registered from "
                            + registrationSource(definition) + ".",
                            "Remove the module's registrations or set its mode to internal."));
                }
            });
        }
        return found;
    }

    /** TL0019: a module persistence bean that is a default candidate and would replace the host's. */
    private List<Diagnostic> defaultCandidates(ConfigurableListableBeanFactory factory, ClassLoader loader,
            Resolution resolution) {
        String exempt = TopologyResolver.standalone(environment);
        Topology topology = resolution.topology();
        Map<String, Diagnostic> found = new TreeMap<>();
        for (String type : OWN_TYPES) {
            ModuleBeans.ownedBy(factory, loader, resolution, type).forEach((name, id) -> {
                BeanDefinition definition = factory.getBeanDefinition(name);
                boolean candidate = !(definition instanceof AbstractBeanDefinition abstractDefinition)
                        || abstractDefinition.isDefaultCandidate();
                if (candidate && topology.internal().contains(id) && !id.equals(exempt)) {
                    found.put(id + "/" + name, new Diagnostic(TopolithCode.TL0019, "module '" + id
                            + "' contributes the default-candidate bean '" + name + "' of type "
                            + simpleName(type) + ", which would replace the host's.",
                            "Declare it with @Bean(defaultCandidate = false) and inject it with @Qualifier."));
                }
            });
        }
        return new ArrayList<>(found.values());
    }

    /** Boot exposes condition outcomes, but the names of beans that caused a back-off only in its message. */
    private static List<Diagnostic> backedOffDefaults(ConfigurableListableBeanFactory factory, ClassLoader loader,
            Resolution resolution) {
        ConditionEvaluationReport report = ConditionEvaluationReport.find(factory);
        if (report == null) {
            return List.of();
        }
        Map<String, Diagnostic> found = new TreeMap<>();
        Set<String> persistenceBeans = new HashSet<>();
        for (String type : OWN_TYPES) {
            persistenceBeans.addAll(ModuleBeans.ownedBy(factory, loader, resolution, type).keySet());
        }
        Set<String> autoConfigurations = new HashSet<>();
        ImportCandidates.load(AutoConfiguration.class, loader).forEach(autoConfigurations::add);
        report.getConditionAndOutcomesBySource().forEach((source, outcomes) -> {
            String configName = source.split("#", 2)[0];
            try {
                Class<?> config = Class.forName(configName, false, loader);
                while (config != null && !AnnotatedElementUtils.hasAnnotation(config, AutoConfiguration.class)
                        && !autoConfigurations.contains(config.getName())) {
                    config = config.getEnclosingClass();
                }
                if (config == null) {
                    return;
                }
            } catch (ClassNotFoundException | LinkageError e) {
                return;
            }
            outcomes.forEach(result -> {
                String message = result.getOutcome().getMessage();
                if (result.getOutcome().isMatch()
                        || !result.getCondition().getClass().getSimpleName().equals("OnBeanCondition")
                        || message == null || !message.contains("@ConditionalOnMissingBean")
                        || !message.contains("found bean")) {
                    return;
                }
                Set<String> names = new HashSet<>();
                String details = message.substring(message.indexOf("found bean"));
                var quoted = QUOTED.matcher(details);
                while (quoted.find()) {
                    names.add(quoted.group(1));
                }
                var typed = TYPED.matcher(details);
                while (typed.find()) {
                    for (String name : typed.group(1).split("[,\\s]+")) {
                        names.add(name.startsWith("&") ? name.substring(1) : name);
                    }
                }
                for (String name : names) {
                    if (!factory.containsBeanDefinition(name)) {
                        continue;
                    }
                    BeanDefinition definition = factory.getBeanDefinition(name);
                    if (definition instanceof AbstractBeanDefinition bean && !bean.isDefaultCandidate()) {
                        continue;
                    }
                    ModuleBeans.owner(definition, resolution).ifPresent(id -> {
                        if (!resolution.topology().internal().contains(id) || persistenceBeans.contains(name)
                                || resolution.topology().ownerOfClass(configName).filter(id::equals).isPresent()) {
                            return;
                        }
                        found.put(id + "/" + name + "/" + source, new Diagnostic(TopolithCode.TL0031,
                                "module '" + id + "' bean '" + name + "' made auto-configuration '" + source
                                        + "' back off.", "Declare the bean @Bean(defaultCandidate = false) and "
                                        + "inject it with a qualifier, or move it to the host."));
                    });
                }
            });
        });
        return new ArrayList<>(found.values());
    }

    private static String simpleName(String type) {
        return type.substring(type.lastIndexOf('.') + 1);
    }

    /** TL0007: a repository for a non-internal module's interface, such as an explicit host @Enable...Repositories. */
    private List<Diagnostic> repositories(ConfigurableListableBeanFactory factory, ClassLoader loader,
            Resolution resolution) {
        Topology topology = resolution.topology();
        Map<String, List<String>> byOwner = new TreeMap<>();
        ModuleBeans.repositoryInterfaces(factory, loader, resolution, ModuleBeans.REPOSITORY_FACTORY_BEAN)
                .forEach((name, owner) -> {
                    if (!owner.isEmpty() && !topology.internal().contains(owner)) {
                        byOwner.computeIfAbsent(owner, key -> new ArrayList<>()).add(name);
                    }
                });
        List<Diagnostic> found = new ArrayList<>();
        byOwner.forEach((owner, names) -> found.add(new Diagnostic(TopolithCode.TL0007, "the host registered "
                + names.size() + " repository interface(s) of module '" + owner + "' (mode "
                + topology.mode(owner).id() + "), such as " + names.get(0) + ".",
                "Narrow the host's repository scan to its own package, or set topolith.modules." + owner
                + ".mode to internal.")));
        return found;
    }

    /**
     * TL0024: a Spring Data repository or MyBatis mapper interface, in an internal module's package or in the host's
     * auto-configuration packages, that no repository or mapper registration covers (D-36). Topolith registers
     * repositories and mappers per module, and TL0018 keeps the host scan away from module packages. A missing store
     * would lose the interface silently. Found by class-file metadata, without loading the interfaces; a store whose
     * library is not on the class path has no interfaces to find.
     */
    private List<Diagnostic> unregistered(ConfigurableListableBeanFactory factory, ClassLoader loader,
            Resolution resolution) {
        Topology topology = resolution.topology();
        // package -> label; an empty label marks a package the check leaves alone (a non-internal module, an api)
        Map<String, String> where = new TreeMap<>();
        if (AutoConfigurationPackages.has(factory)) {
            AutoConfigurationPackages.get(factory).forEach(hostPackage -> where.put(hostPackage, "the host"));
        }
        topology.modulePackages().forEach((id, modulePackage) -> {
            if (modulePackage != null) {
                where.put(modulePackage, topology.internal().contains(id) ? "module '" + id + "'" : "");
            }
        });
        topology.apiPackages().forEach(apiPackage -> {
            if (topology.internal().stream().noneMatch(id -> covers(topology.modulePackages().get(id), apiPackage))) {
                where.putIfAbsent(apiPackage, "");
            }
        });
        where.remove("");
        if (where.values().stream().allMatch(String::isEmpty)) {
            return List.of();
        }
        ClassPathScanningCandidateComponentProvider scanner = interfaceScanner(loader, environment);
        if (scanner == null) {
            return List.of();
        }
        Set<String> covered = new LinkedHashSet<>(ModuleBeans.registeredInterfaceNames(factory, loader,
                ModuleBeans.REPOSITORY_FACTORY_BEAN));
        covered.addAll(ModuleBeans.registeredInterfaceNames(factory, loader, ModuleBeans.MAPPER_FACTORY_BEAN));
        Map<String, Diagnostic> found = new TreeMap<>();
        where.forEach((scanned, label) -> {
            if (label.isEmpty()) {
                return;
            }
            scanner.findCandidateComponents(scanned).forEach(candidate -> {
                String name = candidate.getBeanClassName();
                // the most specific package that holds the interface decides its label, or leaves it alone
                String owner = name == null ? "" : where.entrySet().stream()
                        .filter(entry -> name.startsWith(entry.getKey() + "."))
                        .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                        .map(Map.Entry::getValue).orElse("");
                if (!owner.isEmpty() && !covered.contains(name)) {
                    found.putIfAbsent(name, new Diagnostic(TopolithCode.TL0024, "the interface " + name + " in "
                            + owner + " is a repository or mapper that no registration covers.",
                            "Topolith registers JPA, JDBC, R2DBC and MongoDB repositories and MyBatis mappers; "
                            + "ensure the store infrastructure is available and a mapper has a single host "
                            + "SqlSessionFactory, "
                            + "or have the module register its own (@Enable<Store>Repositories or @MapperScan "
                            + "on its package) "
                            + "and the "
                            + "host declare its own @Enable<Store>Repositories for the host's package. An interface "
                            + "that is unregistered on purpose: annotate it @NoRepositoryBean. With multiple "
                            + "Spring Data stores, "
                            + "annotate the aggregate for its store (for JDBC, @Table), or extend a store-specific "
                            + "repository interface so strict mode can assign it."));
                }
            });
        });
        return new ArrayList<>(found.values());
    }

    private static boolean covers(String modulePackage, String candidate) {
        return modulePackage != null && !modulePackage.isEmpty()
                && (candidate.equals(modulePackage) || candidate.startsWith(modulePackage + "."));
    }

    /**
     * A scanner for Spring Data repository and MyBatis mapper interfaces, or null when neither library is present. It
     * judges {@code @Profile} and {@code @Conditional} against the application's environment, and skips member
     * interfaces, which Spring Data's repository scan skips by default, except {@code @Mapper} ones, which MyBatis's
     * scanner registers.
     */
    private static ClassPathScanningCandidateComponentProvider interfaceScanner(ClassLoader loader,
            Environment environment) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false,
                environment) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface()
                        && (definition.getMetadata().getEnclosingClassName() == null
                                || definition.getMetadata().hasAnnotation(ModuleBeans.MAPPER));
            }
        };
        scanner.setResourceLoader(new DefaultResourceLoader(loader));
        boolean any = false;
        Class<?> repository = ModuleBeans.load(ModuleBeans.REPOSITORY, loader);
        if (repository != null) {
            any = true;
            scanner.addIncludeFilter(new AssignableTypeFilter(repository));
            annotation(ModuleBeans.REPOSITORY_DEFINITION, loader).ifPresent(type ->
                    scanner.addIncludeFilter(new AnnotationTypeFilter(type, false)));
            annotation(ModuleBeans.NO_REPOSITORY_BEAN, loader).ifPresent(type ->
                    scanner.addExcludeFilter(new AnnotationTypeFilter(type, false)));
        }
        Optional<Class<? extends Annotation>> mapper = annotation(ModuleBeans.MAPPER, loader);
        if (mapper.isPresent()) {
            any = true;
            scanner.addIncludeFilter(new AnnotationTypeFilter(mapper.get(), false));
        }
        return any ? scanner : null;
    }

    @SuppressWarnings("unchecked")
    private static Optional<Class<? extends Annotation>> annotation(String name, ClassLoader loader) {
        Class<?> type = ModuleBeans.load(name, loader);
        return type != null && type.isAnnotation() ? Optional.of((Class<? extends Annotation>) type) : Optional.empty();
    }

    /**
     * TL0020: a module's migrations that would not run exactly once: both tools or several master files, a module
     * runner of its own that runs the other tool, several DataSources of its own and no runner (D-112), a tool that
     * is not on the class path, no DataSource for the runner, or a tracking table name that is too long. A host
     * location or changelog that reaches the files is caught when the host's runner bean is created.
     */
    private List<Diagnostic> migrations(ConfigurableListableBeanFactory factory, ClassLoader loader,
            Resolution resolution) {
        List<Diagnostic> found = new ArrayList<>();
        Map<String, Migrations> shipped = ModuleMigrations.withMigrations(resolution.topology(), loader);
        if (shipped.isEmpty()) {
            return found;
        }
        Map<String, List<String>> ownDataSources = ModuleBeans.dataSourcesByModule(factory, loader, resolution);
        Set<String> ownRunners = ModuleBeans.ownRunners(factory, loader, resolution);
        Set<String> ownFlyway = ModuleBeans.flywayRunners(factory, loader, resolution);
        Set<String> ownLiquibase = new TreeSet<>(ModuleBeans.contributors(factory, loader, resolution,
                ModuleBeans.LIQUIBASE));
        boolean flyway = ModuleBeans.load(ModuleBeans.FLYWAY, loader) != null;
        boolean liquibase = ModuleBeans.load(ModuleBeans.LIQUIBASE, loader) != null;
        String dataSource = ModuleBeans.dataSourceProblem(factory, loader);
        boolean hostFlyway = !ModuleBeans.hostBeans(factory, loader, resolution, ModuleBeans.FLYWAY).isEmpty();
        boolean hostLiquibase = !ModuleBeans.hostBeans(factory, loader, resolution, ModuleBeans.LIQUIBASE).isEmpty();
        shipped.forEach((id, migrations) -> {
            String location = ModuleMigrations.path(id);
            Tool tool = migrations.tool();
            if (migrations.flyway() && !migrations.masters().isEmpty()) {
                found.add(new Diagnostic(TopolithCode.TL0020, "module '" + id + "' has both Flyway scripts and a "
                        + "Liquibase changelog under " + location + ".", "Use one tool: remove one of them. A "
                        + "Liquibase-included SQL file named like V1__x.sql counts as a Flyway script; rename it."));
            } else if (migrations.masters().size() > 1) {
                found.add(new Diagnostic(TopolithCode.TL0020, "module '" + id + "' has " + migrations.masters().size()
                        + " master changelogs under " + location + ": " + migrations.masters() + ".",
                        "Keep one " + ModuleMigrations.MASTER + "<yaml|yml|xml|json|sql> file."));
            } else if (ownRunners.contains(id)) {
                // a runner of its own owns the migrations on whichever DataSource (D-112), so it must run this tool
                if (!(tool == Tool.LIQUIBASE ? ownLiquibase : ownFlyway).contains(id)) {
                    found.add(new Diagnostic(TopolithCode.TL0020, "module '" + id + "' has migrations under "
                            + location + " and a runner of its own, but contributes no "
                            + (tool == Tool.LIQUIBASE ? "SpringLiquibase" : "Flyway") + " to run them.",
                            "Declare " + (tool == Tool.LIQUIBASE ? "a SpringLiquibase"
                            : "a Flyway and a FlywayMigrationInitializer") + " bean in the module, or remove the "
                            + "migrations."));
                }
            } else if (ownDataSources.getOrDefault(id, List.of()).size() > 1) {
                found.add(new Diagnostic(TopolithCode.TL0020, "module '" + id + "' has migrations under " + location
                        + " and " + ownDataSources.get(id).size() + " DataSources of its own "
                        + ownDataSources.get(id) + ", but no runner of its own, so which one they run on is "
                        + "ambiguous.", "Declare " + (tool == Tool.LIQUIBASE ? "a SpringLiquibase"
                        : "a Flyway and a FlywayMigrationInitializer") + " bean in the module on the DataSource the "
                        + "migrations belong to, or keep one DataSource in the module."));
            } else if (tool == Tool.FLYWAY ? !flyway : !liquibase) {
                found.add(new Diagnostic(TopolithCode.TL0020, "module '" + id + "' has " + tool.name().toLowerCase()
                        + " migrations under " + location + " but " + (tool == Tool.FLYWAY ? "Flyway" : "Liquibase")
                        + " is not on the class path: Topolith needs "
                        + (tool == Tool.FLYWAY ? "flyway-core" : "liquibase-core") + ".",
                        "Add it to the host, or remove the migrations."));
            } else if (!ownDataSources.containsKey(id) && dataSource != null
                    && !(tool == Tool.FLYWAY ? hostFlyway : hostLiquibase)) {
                found.add(new Diagnostic(TopolithCode.TL0020, "module '" + id + "' has migrations under " + location
                        + " but " + ("none".equals(dataSource) ? "the host has no DataSource to run them on."
                        : "the host has several DataSources, none of them primary, so no connection is "
                        + "unambiguous for them."), "none".equals(dataSource)
                        ? "Add a DataSource to the host, or remove the migrations."
                        : "Mark the host's main DataSource @Primary, or give the host its own "
                        + (tool == Tool.FLYWAY ? "Flyway" : "Liquibase") + " bean."));
            } else if (ModuleMigrations.longestTable(tool, id) > ModuleMigrations.MAX_TABLE_NAME) {
                found.add(new Diagnostic(TopolithCode.TL0020, "a tracking table name of module '" + id
                        + "' is longer than " + ModuleMigrations.MAX_TABLE_NAME + " characters.",
                        "Use a shorter module id."));
            }
        });
        return found;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }
}
