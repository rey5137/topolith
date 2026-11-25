package io.github.rey5137.topolith.spring.test;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import com.tngtech.archunit.library.freeze.TextFileBasedViolationStore;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.ModuleGraph;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Architecture rules derived from the module and API descriptors (R-ARCH-01, R-ARCH-02, D-56). They fail by default
 * (D-4): a module reaches another only through its API packages and only if it declares the other in {@code requires}
 * or {@code optional}; {@code requires} edges form no cycle; contract methods expose no {@code Page}, {@code Slice},
 * {@code Stream}, entity, {@code InputStream} or lazy proxy. Types of {@code io.github.rey5137.topolith.contract} and
 * {@code .annotation} belong to no module (D-54), and classes outside every module are never checked as targets.
 */
@Incubating
public final class TopolithArchitecture {
    private static final String STORE = "freeze.store.default.";
    private static final String MODULES = "META-INF/topolith/modules/";
    private static final String APIS = "META-INF/topolith/apis/";
    private static final String CONTRACT = "io.github.rey5137.topolith.contract";
    private static final String ANNOTATION = "io.github.rey5137.topolith.annotation";
    private static final List<String> PROHIBITED_SUPERTYPES = List.of("org.springframework.data.domain.Slice",
            "java.util.stream.BaseStream", "java.io.InputStream", "org.hibernate.proxy.HibernateProxy",
            "org.hibernate.collection.spi.PersistentCollection", "java.lang.reflect.Proxy");
    /** Separates the two class names that start a non-API access message; the baseline tooling parses them. */
    static final String BASELINE_SEPARATOR = " -> ";
    private static final String ENTITY = "jakarta.persistence.Entity";

    private TopolithArchitecture() {
    }

    /** Verifies the descriptors on the thread's context class loader against the classes of their packages. */
    public static void verify() {
        verify(contextLoader());
    }

    /** Verifies the descriptors found on {@code loader}. */
    public static void verify(ClassLoader loader) {
        verify(loader, false);
    }

    /**
     * Like {@link #verify()}, but each rule is frozen (R-ARCH-03): violations in the ArchUnit store pass, new ones
     * fail. With no store at all nothing is frozen and this behaves as {@link #verify()}. See
     * {@link #verifyFrozen(Collection, Collection, JavaClasses)}.
     */
    public static void verifyFrozen() {
        verify(contextLoader(), true);
    }

    /** Like {@link #verifyFrozen()}, finding the descriptors and classes through {@code loader}. */
    public static void verifyFrozen(ClassLoader loader) {
        verify(loader, true);
    }

    /**
     * Like {@link #verify(Collection, Collection, JavaClasses)}, with every rule frozen in the ArchUnit store
     * ({@code freeze.store.default.path}, default {@code archunit_store}). With no {@code stored.rules} there, nothing
     * is frozen and the rules run unfrozen, so a clean codebase passes and a violation fails. If the store exists but
     * lacks one of the Topolith rules (first use next to an adopter's own frozen rules, or a changed rule text), this
     * fails naming the rule instead of recording its violations; set
     * {@code freeze.store.default.allowStoreCreation=true} for one run to record them. A custom
     * {@code freeze.store} class is used as configured, without that check.
     */
    public static void verifyFrozen(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis,
            JavaClasses classes) {
        ArchConfiguration config = ArchConfiguration.get();
        boolean optedIn = Boolean.parseBoolean(config.getPropertyOrDefault(STORE + "allowStoreCreation", "false"))
                || Boolean.parseBoolean(config.getPropertyOrDefault("freeze.refreeze", "false"));
        boolean custom = config.containsProperty("freeze.store");
        Path stored = Path.of(config.getPropertyOrDefault(STORE + "path", "archunit_store"))
                .resolve("stored.rules");
        boolean exists = Files.exists(stored);
        boolean allowCreation = Boolean.parseBoolean(config.getPropertyOrDefault(STORE + "allowStoreCreation",
                "false"));
        if (!custom && !exists && !allowCreation) {
            verify(modules, apis, classes);
            return;
        }
        if (!custom && exists && !optedIn) {
            TextFileBasedViolationStore store = new TextFileBasedViolationStore();
            store.initialize(config.getSubProperties("freeze.store"));
            for (ArchRule rule : rules(modules, apis)) {
                if (!store.contains(rule)) {
                    throw new AssertionError("The violation store " + stored + " has no entry for the rule '"
                            + rule.getDescription() + "'. Set " + STORE + "allowStoreCreation=true for one run to "
                            + "record its current violations as the baseline.");
                }
            }
        }
        frozenRules(modules, apis).forEach(rule -> rule.check(classes));
    }

    private static void verify(ClassLoader loader, boolean frozen) {
        List<ModuleDescriptor> modules = readModules(loader);
        List<ApiDescriptor> apis = readApis(loader);
        requireModules(modules);
        JavaClasses classes = importPackages(modules, apis, loader);
        if (frozen) {
            verifyFrozen(modules, apis, classes);
        } else {
            verify(modules, apis, classes);
        }
    }

    /** Verifies the given descriptors against {@code classes}; no classpath resource is read. */
    public static void verify(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis,
            JavaClasses classes) {
        rules(modules, apis).forEach(rule -> rule.check(classes));
    }

    static void requireModules(Collection<ModuleDescriptor> modules) {
        if (modules.isEmpty()) {
            throw new AssertionError("No module descriptor under " + MODULES + " was found on the class path, so "
                    + "nothing can be checked. Check that the annotation processor ran and that the JARs carry "
                    + "directory entries.");
        }
    }

    /** Imports the classes of every module and api package of the descriptors from the class path. */
    public static JavaClasses importPackages(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis) {
        return importPackages(modules, apis, contextLoader());
    }

    /** Like {@link #importPackages(Collection, Collection)}, finding the classes through {@code loader}. */
    public static JavaClasses importPackages(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis,
            ClassLoader loader) {
        Set<String> packages = new Ownership(modules, apis).allPackages();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages(packages.toArray(String[]::new));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * The rules, in order: non-API access, undeclared dependency, {@code requires} cycles, descriptor consistency,
     * contract types. {@link #frozenRules} wraps each one in its own frozen baseline.
     */
    public static List<ArchRule> rules(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis) {
        Ownership ownership = new Ownership(modules, apis);
        return List.of(
                classes().should(nonApiAccess(ownership))
                        .as("a module reaches another module only through its API packages (AC-ARCH-01)")
                        .allowEmptyShould(true),
                classes().should(declaredDependency(ownership))
                        .as("a module uses the API of another only if it declares it in requires or optional "
                                + "(AC-ARCH-02)")
                        .allowEmptyShould(true),
                classes().should(acyclic(modules))
                        .as("modules form no requires cycle (AC-ARCH-03)").allowEmptyShould(true),
                classes().should(consistent(modules, ownership))
                        .as("descriptors are consistent: unique module ids and api packages").allowEmptyShould(true),
                classes().should(topolithDependencies(ownership))
                        .as("module code uses only Topolith annotation and contract packages (AC-ARCH-19)")
                        .allowEmptyShould(true),
                classes().should(contractTypes(ownership, apis))
                        .as("contract methods expose only value types (AC-ARCH-05)").allowEmptyShould(true));
    }

    /**
     * The {@link #rules} each wrapped in ArchUnit's {@code FreezingArchRule} with its own store entry, keyed by the
     * rule text. The store is ArchUnit's: {@code freeze.store.default.path} in {@code archunit.properties}. A new
     * violation of a stored rule fails and is not recorded, and a violation that is gone is dropped
     * ({@code allowStoreUpdate} defaults to true). Recording a first baseline is the explicit opt-in
     * {@code freeze.store.default.allowStoreCreation=true} for one run; {@code freeze.refreeze=true} only re-records
     * an existing store. Prefer {@link #verifyFrozen(Collection, Collection, JavaClasses)}, which also handles a
     * missing store and a store lacking these rules. The descriptor rules (cycles, consistency) report from
     * {@code finish} and freeze like the others, by message text.
     */
    public static List<ArchRule> frozenRules(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis) {
        return rules(modules, apis).stream().map(rule -> (ArchRule) FreezingArchRule.freeze(rule)).toList();
    }

    private static ArchCondition<JavaClass> topolithDependencies(Ownership ownership) {
        return new ArchCondition<>("depend only on Topolith annotation and contract packages") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                if (ownership.owner(type.getPackageName()) == null) {
                    return;
                }
                for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getPackageName();
                    if ((target.equals("io.github.rey5137.topolith")
                            || target.startsWith("io.github.rey5137.topolith.")) && !isShared(target)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> nonApiAccess(Ownership ownership) {
        return crossModule("not depend on a non-API class of another module", ownership, (owner, target, dep) -> {
            if (ownership.isApi(dep.getTargetClass().getPackageName())) {
                return null;
            }
            return dep.getOriginClass().getName() + BASELINE_SEPARATOR + dep.getTargetClass().getName()
                    + ": module '" + owner + "' depends on '" + dep.getTargetClass().getName() + "' of module '"
                    + target + "', which is not in an API package: " + dep.getDescription();
        });
    }

    private static ArchCondition<JavaClass> declaredDependency(Ownership ownership) {
        Map<String, ModuleDescriptor> byId = new TreeMap<>();
        ownership.modules().forEach(module -> byId.put(module.id(), module));
        return crossModule("not use the API of an undeclared module", ownership, (owner, target, dep) -> {
            ModuleDescriptor from = byId.get(owner);
            if (!ownership.isApi(dep.getTargetClass().getPackageName()) || from == null
                    || from.requires().contains(target) || from.optional().contains(target)) {
                return null;
            }
            return "module '" + owner + "' uses the API of module '" + target + "' but declares it in neither "
                    + "requires nor optional: " + dep.getDescription();
        });
    }

    private interface Check {
        String violation(String owner, String target, Dependency dependency);
    }

    private static ArchCondition<JavaClass> crossModule(String description, Ownership ownership, Check check) {
        return new ArchCondition<>(description) {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                String owner = ownership.owner(type.getPackageName());
                if (owner == null) {
                    return;
                }
                for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    String target = isShared(targetPackage) ? null : ownership.owner(targetPackage);
                    if (target == null || target.equals(owner) || dependency.getTargetClass().isArray()) {
                        continue;
                    }
                    String message = check.violation(owner, target, dependency);
                    if (message != null) {
                        events.add(SimpleConditionEvent.violated(dependency, message));
                    }
                }
            }
        };
    }

    private static boolean isShared(String packageName) {
        return packageName.equals(CONTRACT) || packageName.startsWith(CONTRACT + ".")
                || packageName.equals(ANNOTATION) || packageName.startsWith(ANNOTATION + ".");
    }

    private static ArchCondition<JavaClass> acyclic(Collection<ModuleDescriptor> modules) {
        return onceOverDescriptors("have no requires cycle", () -> {
            try {
                ModuleGraph.of(modules);
                return List.of();
            } catch (TopolithException e) {
                return e.diagnostics().stream().filter(diagnostic -> diagnostic.code() == TopolithCode.TL0008)
                        .map(diagnostic -> diagnostic.code() + ": " + diagnostic.description()).toList();
            }
        });
    }

    private static ArchCondition<JavaClass> consistent(Collection<ModuleDescriptor> modules, Ownership ownership) {
        return onceOverDescriptors("have unique module ids and api packages", () -> {
            List<String> problems = new ArrayList<>();
            try {
                ModuleGraph.of(modules);
            } catch (TopolithException e) {
                e.diagnostics().stream().filter(diagnostic -> diagnostic.code() == TopolithCode.TL0006)
                        .forEach(diagnostic -> problems.add(diagnostic.code() + ": " + diagnostic.description()));
            }
            ownership.contestedApiPackages().forEach((name, ids) -> problems.add("api package '" + name
                    + "' is listed by more than one descriptor (" + String.join(", ", ids)
                    + "), so its owner is ambiguous"));
            return problems;
        });
    }

    /** A condition over the descriptors, not the classes: it reports once, in {@code finish}. */
    private static ArchCondition<JavaClass> onceOverDescriptors(String description,
            Supplier<List<String>> problems) {
        return new ArchCondition<>(description) {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
            }

            @Override
            public void finish(ConditionEvents events) {
                problems.get().forEach(problem -> events.add(SimpleConditionEvent.violated(problem, problem)));
            }
        };
    }

    private static ArchCondition<JavaClass> contractTypes(Ownership ownership, Collection<ApiDescriptor> apis) {
        Map<String, Set<String>> provides = new TreeMap<>();
        ownership.modules().forEach(module -> provides.computeIfAbsent(module.id(), id -> new TreeSet<>())
                .addAll(module.provides()));
        apis.forEach(api -> provides.computeIfAbsent(api.id(), id -> new TreeSet<>())
                .addAll(api.provides()));
        return new ArchCondition<>("expose only value types in contracts") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                String module = ownership.isApi(type.getPackageName()) ? ownership.owner(type.getPackageName()) : null;
                if (module == null || !isContract(type, provides.getOrDefault(module, Set.of()))) {
                    return;
                }
                for (JavaMethod method : type.getAllMethods()) {
                    if (method.getOwner().isEquivalentTo(Object.class)) {
                        continue;
                    }
                    String subject = "contract method " + method.getFullName();
                    checkType(module, subject, "return type", method.getReturnType(), events);
                    method.getParameterTypes().forEach(parameter ->
                            checkType(module, subject, "parameter", parameter, events));
                }
                type.getInterfaces().forEach(parent -> checkType(module, "contract " + type.getName(),
                        "super-interface", parent, events));
            }
        };
    }

    private static boolean isContract(JavaClass type, Set<String> provides) {
        if (!type.isInterface() || type.isAnnotation() || !type.getModifiers().contains(JavaModifier.PUBLIC)) {
            return false;
        }
        return provides.isEmpty() || provides.contains(type.getName());
    }

    private static void checkType(String module, String subject, String role, JavaType type,
            ConditionEvents events) {
        for (JavaClass used : type.getAllInvolvedRawTypes()) {
            String reason = prohibited(used);
            if (reason != null) {
                events.add(SimpleConditionEvent.violated(used, subject
                        + " of module '" + module + "' exposes " + reason + " '" + used.getName() + "' in its "
                        + role));
            }
        }
    }

    private static String prohibited(JavaClass type) {
        if (type.isAnnotatedWith(ENTITY)) {
            return "the entity";
        }
        for (String name : PROHIBITED_SUPERTYPES) {
            if (type.isAssignableTo(name)) {
                return "the prohibited type";
            }
        }
        return null;
    }

    static ClassLoader contextLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : TopolithArchitecture.class.getClassLoader();
    }

    static List<ModuleDescriptor> readModules(ClassLoader loader) {
        return Descriptors.parseModules(read(loader, MODULES));
    }

    static List<ApiDescriptor> readApis(ClassLoader loader) {
        return Descriptors.parseApis(read(loader, APIS));
    }

    private static Map<String, InputStream> read(ClassLoader loader, String directory) {
        Map<String, InputStream> files = new TreeMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver(loader)
                    .getResources("classpath*:" + directory + "*.properties")) {
                files.putIfAbsent(resource.getURL().toString(),
                        new ByteArrayInputStream(resource.getContentAsByteArray()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the descriptors under " + directory, e);
        }
        return files;
    }
}
