package io.github.rey5137.topolith.spring.test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.core.Mode;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContextAnnotationUtils;

/** Installs explicit mode properties and context-owned mock servers. */
@Incubating
public final class TopolithContextCustomizerFactory implements ContextCustomizerFactory {
    static String beanName(String id) { return TopolithMockServer.class.getName() + "." + id; }

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> config) {
        var modes = new TreeMap<String, Mode>();
        collectModes(testClass, modes, new HashSet<>());
        return modes.isEmpty() ? null : new Customizer(Map.copyOf(modes));
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

    private record Customizer(Map<String, Mode> modes) implements ContextCustomizer {
        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration configuration) {
            Map<String, Object> properties = new LinkedHashMap<>();
            DefaultListableBeanFactory beans = (DefaultListableBeanFactory) context.getBeanFactory();
            modes.forEach((id, mode) -> {
                String prefix = "topolith.modules." + id + ".";
                properties.put(prefix + "mode", mode.id());
                if (mode == Mode.EXTERNAL) {
                    var server = new TopolithMockServer(id);
                    String name = beanName(id);
                    beans.registerSingleton(name, server);
                    beans.registerDisposableBean(name, server::close);
                    properties.put(prefix + "remote.url", server.url().toString());
                }
            });
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("topolithTestModes", properties));
        }
    }
}
