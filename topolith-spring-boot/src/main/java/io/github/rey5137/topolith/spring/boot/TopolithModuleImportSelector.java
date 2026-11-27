package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.TypeFilterUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ComponentScans;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ScannedGenericBeanDefinition;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ClassUtils;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.TypeFilter;

/**
 * Imports the component-scan candidates under each internal module's package (D-30): the module's package, less every
 * known api package (exact, D-27), with Boot's {@code TypeExcludeFilter} and {@code AutoConfigurationExcludeFilter}
 * applied as {@code @SpringBootApplication} does. The classes come back as names, in module id then class name order
 * (INV-9), and Spring parses them at once, so the module's {@code @Bean}s exist before Boot's conditions run. Imported
 * classes get fully qualified default bean names; an explicit {@code @Component("x")} name is kept.
 */
final class TopolithModuleImportSelector
        implements ImportSelector, BeanFactoryAware, EnvironmentAware, BeanClassLoaderAware, ResourceLoaderAware {
    private static final Logger LOG = LoggerFactory.getLogger(TopolithModuleImportSelector.class);

    private BeanFactory beanFactory;
    private Environment environment;
    private ClassLoader classLoader;
    private ResourceLoader resourceLoader;

    @Override
    public String[] selectImports(AnnotationMetadata importingClassMetadata) {
        if (ClassUtils.isPresent("org.springframework.cloud.context.environment.EnvironmentChangeEvent", classLoader)
                && !ClassUtils.isPresent("io.github.rey5137.topolith.spring.cloud.TopolithRefreshListener",
                        classLoader)) {
            LOG.warn(new Diagnostic(TopolithCode.TL0014, "Spring Cloud context classes are present but "
                    + "topolith-spring-cloud is not, so Topolith ignores configuration refreshes.",
                    "Add topolith-spring-cloud for refresh handling.").toString());
        }
        TopologyResolver.Resolution resolution = TopologyResolver.resolve(beanFactory, environment, classLoader);
        Topology topology = resolution.topology();
        ExternalClientProperties.register(environment, topology);
        checkHostScan(topology);
        List<String> imports = new ArrayList<>();
        for (String id : topology.internal()) {
            imports.addAll(scan(topology, id));
        }
        if (!topology.internal().isEmpty()
                && ClassUtils.isPresent("org.springframework.web.servlet.DispatcherServlet", classLoader)) {
            imports.add("io.github.rey5137.topolith.spring.boot.ModuleErrorsConfiguration");
            imports.add("io.github.rey5137.topolith.spring.boot.ExposureConfiguration");
            if (!resolution.exposed().isEmpty()) {
                imports.add("io.github.rey5137.topolith.spring.boot.ExposedApiVersionConfiguration");
            }
        }
        if (!topology.internal().isEmpty()) {
            imports.add("io.github.rey5137.topolith.spring.boot.ContractBoundaryConfiguration");
        }
        if (!resolution.exposed().isEmpty()) {
            imports.add("io.github.rey5137.topolith.spring.boot.ReactiveExposureConfiguration");
        }
        boolean externals = !topology.idsOf(Mode.EXTERNAL).isEmpty();
        boolean loopbacks = !topology.idsOf(Mode.LOOPBACK).isEmpty();
        if ((!topology.internal().isEmpty() || externals)
                && ClassUtils.isPresent("io.micrometer.observation.ObservationRegistry", classLoader)) {
            imports.add("io.github.rey5137.topolith.spring.boot.ModuleCallObservationConfiguration");
        }
        if (!topology.internal().isEmpty() || externals || loopbacks) {
            imports.add("io.github.rey5137.topolith.spring.boot.ContextPropagationConfiguration");
        }
        if ((externals || loopbacks) && ClassUtils.isPresent(
                "org.springframework.web.service.registry.AbstractHttpServiceRegistrar", classLoader)) {
            imports.add("io.github.rey5137.topolith.spring.boot.ExternalHttpServicesRegistrar");
            imports.add("io.github.rey5137.topolith.spring.boot.ExternalErrorsConfiguration");
            imports.add("io.github.rey5137.topolith.spring.boot.OutgoingAuthConfiguration");
            if (ClassUtils.isPresent("org.springframework.boot.http.client.HttpClientSettings", classLoader)) {
                if (externals) {
                    imports.add("io.github.rey5137.topolith.spring.boot.ExternalClientSwapConfiguration");
                }
                if (loopbacks) {
                    imports.add("io.github.rey5137.topolith.spring.boot.LoopbackConfiguration");
                }
            }
        }
        return imports.toArray(String[]::new);
    }

    /** TL0007, raised early: the host's own component scan already registered a non-internal module's class. */
    private void checkHostScan(Topology topology) {
        if (!(beanFactory instanceof ConfigurableListableBeanFactory factory)) {
            return;
        }
        checkDeclaredScans(factory, topology);
        Map<String, List<String>> scanned = new TreeMap<>();
        for (String name : factory.getBeanDefinitionNames()) {
            BeanDefinition definition = factory.getBeanDefinition(name);
            String className = definition instanceof ScannedGenericBeanDefinition ? definition.getBeanClassName()
                    : null;
            String owner = className == null ? null : topology.ownerOfClass(className).orElse(null);
            if (owner != null && !topology.internal().contains(owner)) {
                scanned.computeIfAbsent(owner, key -> new ArrayList<>()).add(className);
            }
        }
        List<Diagnostic> found = new ArrayList<>();
        scanned.forEach((owner, classes) -> {
            Collections.sort(classes);
            found.add(new Diagnostic(TopolithCode.TL0007, "the host's component scan registered " + classes.size()
                    + " class(es) of module '" + owner + "' (mode " + topology.mode(owner).id() + "), such as "
                    + classes.get(0) + ".", "Narrow the host's scan to its own package, or set topolith.modules."
                    + owner + ".mode to internal."));
        });
        if (!found.isEmpty()) {
            throw new TopolithException(found);
        }
    }

    private void checkDeclaredScans(ConfigurableListableBeanFactory factory, Topology topology) {
        Map<String, String> annotations = Map.of(
                "org.springframework.context.annotation.ComponentScan", "component scan",
                "org.springframework.boot.web.servlet.ServletComponentScan", "servlet-component scan",
                "org.springframework.boot.web.server.servlet.context.ServletComponentScan", "servlet-component scan",
                "org.springframework.boot.context.properties.ConfigurationPropertiesScan",
                "configuration-properties scan");
        String standalone = TopologyResolver.standalone(environment);
        List<Diagnostic> found = new ArrayList<>();
        for (String name : factory.getBeanDefinitionNames()) {
            BeanDefinition definition = factory.getBeanDefinition(name);
            if (!(definition instanceof AnnotatedBeanDefinition annotated)) {
                continue;
            }
            AnnotationMetadata metadata = annotated.getMetadata();
            if (topology.ownerOfClass(metadata.getClassName()).isPresent()) {
                continue;
            }
            annotations.forEach((annotation, label) -> {
                List<Map<String, Object>> scans = new ArrayList<>();
                if (annotation.equals(ComponentScan.class.getName())) {
                    var single = metadata.getAnnotationAttributes(annotation, false);
                    if (single != null) {
                        scans.add(single);
                    }
                    var repeated = metadata.getAnnotationAttributes(ComponentScans.class.getName(), false);
                    if (repeated != null) {
                        var values = (org.springframework.core.annotation.AnnotationAttributes[]) repeated.get("value");
                        for (var item : values) {
                            scans.add(item);
                        }
                    }
                } else {
                    var attributes = metadata.getAnnotationAttributes(annotation, true);
                    if (attributes != null) {
                        scans.add(attributes);
                    }
                }
                scans.forEach(attributes -> {
                Set<String> packages = new TreeSet<>();
                for (String key : List.of("value", "basePackages", "basePackageClasses")) {
                    Object value = attributes.get(key);
                    if (value instanceof Class<?>[] classes) {
                        for (Class<?> type : classes) {
                            packages.add(ClassUtils.getPackageName(type));
                        }
                    } else if (value instanceof String[] values) {
                        for (String item : values) {
                            String resolved = environment.resolvePlaceholders(item);
                            if (key.equals("basePackageClasses")) {
                                resolved = ClassUtils.getPackageName(resolved);
                            }
                            packages.add(resolved);
                        }
                    }
                }
                if (packages.isEmpty()) {
                    packages.add(ClassUtils.getPackageName(metadata.getClassName()));
                }
                for (String base : packages) {
                    topology.modulePackages().forEach((id, modulePackage) -> {
                        if (id.equals(standalone) || modulePackage == null
                                || !(base.isEmpty() || modulePackage.equals(base)
                                        || modulePackage.startsWith(base + "."))) {
                            return;
                        }
                        boolean internal = topology.internal().contains(id);
                        if (!internal && annotation.endsWith("ComponentScan")
                                && !annotation.endsWith("ServletComponentScan")) {
                            return; // Existing registration check accounts for component-scan filters.
                        }
                        if (internal && annotation.equals(ComponentScan.class.getName())
                                && excludedByScan(attributes, modulePackage, factory)) {
                            return;
                        }
                        found.add(new Diagnostic(internal ? TopolithCode.TL0018 : TopolithCode.TL0007,
                                "the host's " + label + " package '" + base + "' covers module '" + id
                                        + "' (" + modulePackage + ").",
                                "Narrow the host's scan to its own package."));
                    });
                }
                });
            });
        }
        if (!found.isEmpty()) {
            throw new TopolithException(found);
        }
    }

    private boolean excludedByScan(Map<String, Object> attributes, String modulePackage,
            ConfigurableListableBeanFactory factory) {
        Object value = attributes.get("excludeFilters");
        if (!(value instanceof Map[] filters)
                || filters.length == 0 || !(factory instanceof BeanDefinitionRegistry registry)) {
            return false;
        }
        var scanner = new ClassPathScanningCandidateComponentProvider(true, environment);
        if (resourceLoader != null) {
            scanner.setResourceLoader(resourceLoader);
        }
        if (scanner.findCandidateComponents(modulePackage).isEmpty()) {
            return false;
        }
        for (var filter : filters) {
            TypeFilterUtils.createTypeFiltersFor(
                    org.springframework.core.annotation.AnnotationAttributes.fromMap(filter),
                    environment, resourceLoader, registry)
                    .forEach(scanner::addExcludeFilter);
        }
        return scanner.findCandidateComponents(modulePackage).isEmpty();
    }

    private List<String> scan(Topology topology, String id) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true,
                environment);
        if (resourceLoader != null) {
            scanner.setResourceLoader(resourceLoader);
        }
        TypeExcludeFilter typeExclude = new TypeExcludeFilter();
        typeExclude.setBeanFactory(beanFactory);
        AutoConfigurationExcludeFilter autoConfigurationExclude = new AutoConfigurationExcludeFilter();
        autoConfigurationExclude.setBeanClassLoader(classLoader);
        Set<String> apiPackages = topology.apiPackages();
        TypeFilter apiExclude = (reader, factory) -> {
            String className = reader.getClassMetadata().getClassName();
            return apiPackages.contains(className.substring(0, Math.max(className.lastIndexOf('.'), 0)));
        };
        scanner.addExcludeFilter(typeExclude);
        scanner.addExcludeFilter(autoConfigurationExclude);
        scanner.addExcludeFilter(apiExclude);
        Set<String> names = new TreeSet<>();
        scanner.findCandidateComponents(topology.modulePackages().get(id))
                .forEach(candidate -> names.add(candidate.getBeanClassName()));
        return new ArrayList<>(names);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }
}
