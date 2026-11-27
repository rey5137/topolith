package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.util.ReflectionUtils;

/** Reflective access to the listed contract methods and their binding types in an AOT application (D-125). */
final class ListedRouteHints implements BeanFactoryInitializationAotProcessor {
    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory factory) {
        TopologyResolver.Resolution resolution = factory.getBean(TopologyResolver.RESOLUTION_BEAN,
                TopologyResolver.Resolution.class);
        return (generation, code) -> register(generation.getRuntimeHints(), resolution, factory.getBeanClassLoader());
    }

    static void register(RuntimeHints hints, TopologyResolver.Resolution resolution, ClassLoader loader) {
        BindingReflectionHintsRegistrar binding = new BindingReflectionHintsRegistrar();
        for (String id : resolution.exposed()) {
            for (String name : resolution.listed().getOrDefault(id, Set.of())) {
                Class<?> contract = ModuleBeans.load(name, loader);
                if (contract == null || !contract.isInterface()) {
                    continue;
                }
                for (Method method : contract.getMethods()) {
                    if (Modifier.isAbstract(method.getModifiers()) && !ReflectionUtils.isObjectMethod(method)) {
                        hints.reflection().registerMethod(method, ExecutableMode.INVOKE);
                        binding.registerReflectionHints(hints.reflection(), method.getGenericReturnType());
                        binding.registerReflectionHints(hints.reflection(), method.getGenericParameterTypes());
                    }
                }
            }
        }
    }
}
