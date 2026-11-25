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

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportSelector;
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
    private BeanFactory beanFactory;
    private Environment environment;
    private ClassLoader classLoader;
    private ResourceLoader resourceLoader;

    @Override
    public String[] selectImports(AnnotationMetadata importingClassMetadata) {
        Topology topology = TopologyResolver.resolve(beanFactory, environment, classLoader).topology();
        ExternalClientProperties.register(environment, topology);
        checkHostScan(topology);
        List<String> imports = new ArrayList<>();
        for (String id : topology.internal()) {
            imports.addAll(scan(topology, id));
        }
        if (!topology.internal().isEmpty()
                && ClassUtils.isPresent("org.springframework.web.servlet.DispatcherServlet", classLoader)) {
            imports.add("io.github.rey5137.topolith.spring.boot.ModuleErrorsConfiguration");
        }
        if (!topology.idsOf(Mode.EXTERNAL).isEmpty() && ClassUtils.isPresent(
                "org.springframework.web.service.registry.AbstractHttpServiceRegistrar", classLoader)) {
            imports.add("io.github.rey5137.topolith.spring.boot.ExternalHttpServicesRegistrar");
            imports.add("io.github.rey5137.topolith.spring.boot.ExternalErrorsConfiguration");
        }
        return imports.toArray(String[]::new);
    }

    /** TL0007, raised early: the host's own component scan already registered a non-internal module's class. */
    private void checkHostScan(Topology topology) {
        if (!(beanFactory instanceof ConfigurableListableBeanFactory factory)) {
            return;
        }
        Map<String, List<String>> scanned = new TreeMap<>();
        for (String name : factory.getBeanDefinitionNames()) {
            BeanDefinition definition = factory.getBeanDefinition(name);
            String className = definition instanceof ScannedGenericBeanDefinition ? definition.getBeanClassName()
                    : null;
            String owner = className == null ? null : topology.ownerOfClass(className).orElse(null);
            if (owner != null && topology.mode(owner) != Mode.INTERNAL) {
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
