package io.github.rey5137.topolith.spring.boot;

import java.util.HashMap;
import java.util.Map;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

/**
 * Resolves the topology once per bean factory: the filter runs before the selector, and the first caller registers
 * the result as the {@code topolithTopology} singleton (INV-9; the actuator and the topology comparison read it).
 * Failures are {@link io.github.rey5137.topolith.core.TopolithException}s.
 */
final class TopologyResolver {
    static final String TOPOLOGY_BEAN = "topolithTopology";
    static final String RESOLUTION_BEAN = "topolithResolution";
    static final String MODES = "topolith.modules";
    static final String STANDALONE = "topolith.standalone";

    /** Only {@code topolith.modules.<id>.mode} is bound in M1.3; other keys of the module are ignored. */
    record ModeOnly(String mode) {
    }

    /** The topology with the discovery facts the filter and the persistence checks need. */
    record Resolution(Topology topology, Map<String, String> importOwners, Map<String, String> rootOwners) {
    }

    private TopologyResolver() {
    }

    /** The id named by {@code topolith.standalone}, trimmed, or null. */
    static String standalone(Environment environment) {
        String value = environment == null ? null : environment.getProperty(STANDALONE);
        return value == null || value.isBlank() ? null : value.trim();
    }

    static Resolution resolve(BeanFactory beanFactory, Environment environment, ClassLoader loader) {
        if (!(beanFactory instanceof SingletonBeanRegistry registry)) {
            return compute(environment, loader);
        }
        synchronized (registry) {
            if (registry.containsSingleton(RESOLUTION_BEAN)) {
                return (Resolution) registry.getSingleton(RESOLUTION_BEAN);
            }
            Resolution resolution = compute(environment, loader);
            registry.registerSingleton(RESOLUTION_BEAN, resolution);
            registry.registerSingleton(TOPOLOGY_BEAN, resolution.topology());
            return resolution;
        }
    }

    /** A {@code topolith.modules.<id>} value that is not a settings group, such as {@code ...user=internal}: TL0001. */
    private static Diagnostic notAMode(BindException e) {
        String name = e.getName() == null ? MODES : e.getName().toString();
        String rest = name.startsWith(MODES + ".") ? name.substring(MODES.length() + 1) : "";
        String id = rest.isEmpty() ? "<id>" : rest.split("\\.", 2)[0];
        return new Diagnostic(TopolithCode.TL0001, "the property '" + name + "' is not a mode setting for module '"
                + id + "'.", "Set topolith.modules." + id + ".mode to internal, external or none.");
    }

    private static Resolution compute(Environment environment, ClassLoader loader) {
        Environment env = environment == null ? new StandardEnvironment() : environment;
        ClassLoader classLoader = loader == null ? Thread.currentThread().getContextClassLoader() : loader;
        DescriptorDiscovery.Found found = DescriptorDiscovery.discover(classLoader);
        Map<String, String> declared = new HashMap<>();
        try {
            Binder.get(env).bind(MODES, Bindable.mapOf(String.class, ModeOnly.class))
                    .ifBound(bound -> bound.forEach((id, value) -> declared.put(id, value.mode())));
        } catch (BindException e) {
            throw new TopolithException(notAMode(e));
        }
        String standalone = env.getProperty(STANDALONE);
        Topology topology = Topology.resolve(found.modules(), found.apis(), declared,
                standalone == null || standalone.isBlank() ? null : standalone.trim());
        return new Resolution(topology, found.importOwners(), found.rootOwners());
    }
}
