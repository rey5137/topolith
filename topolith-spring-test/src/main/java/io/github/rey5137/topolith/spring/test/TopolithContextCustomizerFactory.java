package io.github.rey5137.topolith.spring.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Set;

import io.github.rey5137.topolith.core.Mode;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.DefaultPropertySourceFactory;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceDescriptor;
import org.springframework.core.io.support.PropertySourceFactory;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContextAnnotationUtils;

/** Installs explicit mode properties and context-owned mock servers. */
final class TopolithContextCustomizerFactory implements ContextCustomizerFactory {
    static String beanName(String id) { return TopolithMockServer.class.getName() + "." + id; }

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> config) {
        var modes = new TreeMap<String, Mode>();
        collectModes(testClass, modes, new HashSet<>());
        var byValue = new ArrayList<TopolithByValue>();
        collectByValue(testClass, byValue, new HashSet<>());
        Set<String> ids = null;
        if (!byValue.isEmpty()) {
            ids = new TreeSet<>();
            for (TopolithByValue annotation : byValue) { ids.addAll(List.of(annotation.value())); }
            // an annotation with no ids selects every internal module
            if (byValue.stream().anyMatch(annotation -> annotation.value().length == 0)) { ids.clear(); }
            ids = Set.copyOf(ids);
        }
        return modes.isEmpty() && ids == null ? null : new Customizer(Map.copyOf(modes), ids);
    }

    private static void collectByValue(Class<?> type, List<TopolithByValue> found, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) { return; }
        TopolithByValue declared = type.getDeclaredAnnotation(TopolithByValue.class);
        if (declared != null) { found.add(declared); }
        collectByValue(type.getSuperclass(), found, visited);
        if (TestContextAnnotationUtils.searchEnclosingClass(type)) {
            collectByValue(type.getEnclosingClass(), found, visited);
        }
    }

    private static void collectModes(Class<?> type, Map<String, Mode> modes, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) { return; }
        Map<String, Mode> declared = new LinkedHashMap<>();
        for (TopolithModuleMode annotation : type.getDeclaredAnnotationsByType(TopolithModuleMode.class)) {
            if (declared.putIfAbsent(annotation.id(), annotation.mode()) != null) {
                throw new IllegalArgumentException("Duplicate mode for module " + annotation.id());
            }
        }
        declared.forEach(modes::putIfAbsent);
        collectModes(type.getSuperclass(), modes, visited);
        if (TestContextAnnotationUtils.searchEnclosingClass(type)) {
            collectModes(type.getEnclosingClass(), modes, visited);
        }
    }

    static final String SOURCE = "topolithTestModes";
    private static final Set<String> TEST_SOURCES = Set.of("Inlined Test Properties", "Dynamic Test Properties");

    /**
     * Fails when a test-level source (inlined test properties, {@code @TestPropertySource} files, dynamic properties)
     * sets a key the annotation also sets, to another value: the annotation overrides application config only.
     *
     * <p>The files are read again from the descriptors, with placeholders resolved and the declared factory, so the
     * check does not depend on the names a factory gives its sources. A {@code DynamicPropertyRegistrar} bean
     * registers only while singletons are created, so the check runs again once the context has refreshed.
     */
    private record ConflictCheck(Map<String, Object> selected, MergedContextConfiguration configuration,
            ConfigurableEnvironment environment) implements BeanFactoryPostProcessor, PriorityOrdered {
        @Override
        public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beans) { check(); }

        void check() {
            List<PropertySource<?>> sources = new ArrayList<>();
            for (PropertySource<?> source : environment.getPropertySources()) {
                if (TEST_SOURCES.contains(source.getName())) { sources.add(source); }
            }
            if (configuration != null) {
                for (PropertySourceDescriptor descriptor : configuration.getPropertySourceDescriptors()) {
                    sources.addAll(read(descriptor));
                }
            }
            for (PropertySource<?> source : sources) {
                selected.forEach((key, value) -> {
                    Object other = source.getProperty(key);
                    if (other != null && !other.toString().trim().equalsIgnoreCase(value.toString().trim())) {
                        int start = "topolith.modules.".length();
                        String id = key.substring(start, key.indexOf('.', start));
                        throw new IllegalStateException("Module '" + id + "': @TopolithModuleMode selects " + key
                                + "='" + value + "' but test property source '" + source.getName() + "' sets '"
                                + other + "'. The annotation overrides application config only; remove one.");
                    }
                });
            }
        }

        private List<PropertySource<?>> read(PropertySourceDescriptor descriptor) {
            var loader = new DefaultResourceLoader();
            PropertySourceFactory factory = descriptor.propertySourceFactory() == null
                    ? new DefaultPropertySourceFactory() : BeanUtils.instantiateClass(descriptor.propertySourceFactory());
            List<PropertySource<?>> sources = new ArrayList<>();
            for (String location : descriptor.locations()) {
                Resource resource = loader.getResource(environment.resolveRequiredPlaceholders(location));
                try {
                    sources.add(factory.createPropertySource(descriptor.name(),
                            new EncodedResource(resource, descriptor.encoding())));
                } catch (IOException e) {
                    // an optional or missing file was already reported, or tolerated, when the environment was built
                }
            }
            return sources;
        }
    }

    /** {@code byValue} is null without {@link TopolithByValue}, and empty for every internal module. */
    private record Customizer(Map<String, Mode> modes, Set<String> byValue) implements ContextCustomizer {
        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration configuration) {
            if (byValue != null) { ByValue.install(context, byValue); }
            if (modes.isEmpty()) { return; }
            Map<String, Object> properties = new LinkedHashMap<>();
            DefaultListableBeanFactory beans = (DefaultListableBeanFactory) context.getBeanFactory();
            modes.forEach((id, mode) -> {
                String url = null;
                if (mode == Mode.EXTERNAL) {
                    var server = new TopolithMockServer(id);
                    String name = beanName(id);
                    beans.registerSingleton(name, server);
                    beans.registerDisposableBean(name, server::close);
                    url = server.url().toString();
                }
                properties.putAll(ModeProperties.of(id, mode.id(), url));
            });
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource(SOURCE, properties));
            // after every customizer has run, so a dynamic source added later is seen too
            var check = new ConflictCheck(properties, configuration, context.getEnvironment());
            context.addBeanFactoryPostProcessor(check);
            context.addApplicationListener((ApplicationListener<ContextRefreshedEvent>) event -> check.check());
        }
    }
}
