package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;

import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.ModuleDescriptor;
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
    record Resolution(Topology topology, Map<String, String> importOwners, Map<String, String> rootOwners,
            List<String> versionWarnings) {
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
        Topology topology = Topology.resolve(found.modules(), found.apis(), declared, standalone(env));
        List<Diagnostic> problems = new ArrayList<>();
        Binder binder = Binder.get(env);
        topology.idsOf(Mode.EXTERNAL).forEach(id -> {
            String prefix = MODES + "." + id + ".remote.";
            String url = binder.bind(prefix + "url", String.class).orElse("");
            String service = binder.bind(prefix + "service-id", String.class).orElse("");
            if (url.isBlank() == service.isBlank()) {
                problems.add(new Diagnostic(TopolithCode.TL0003, "external module '" + id
                        + "' must set exactly one of " + prefix + "url and " + prefix + "service-id.",
                        "Set exactly one of " + prefix + "url and " + prefix + "service-id."));
            }
        });
        if (!problems.isEmpty()) {
            throw new TopolithException(problems);
        }
        List<String> warnings = new ArrayList<>();
        for (ApiDescriptor api : found.apis()) {
            if (topology.mode(api.id()) != Mode.INTERNAL) {
                continue;
            }
            for (ModuleDescriptor module : found.modules()) {
                if (api.id().equals(module.id()) && !major(api.apiVersion()).equals(major(module.apiVersion()))) {
                    warnings.add(new Diagnostic(TopolithCode.TL0023, "module '" + api.id() + "' has API version "
                            + api.apiVersion() + " and implementation version " + module.apiVersion() + ".",
                            "Align the host API JAR and the implementation JAR major versions.").toString());
                }
            }
        }
        return new Resolution(topology, found.importOwners(), found.rootOwners(), List.copyOf(warnings));
    }

    private static BigInteger major(String version) {
        return new BigInteger(version.split("[.-]", 2)[0]);
    }
}
