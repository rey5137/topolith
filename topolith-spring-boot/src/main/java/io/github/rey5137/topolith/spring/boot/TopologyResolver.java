package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;

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
            List<String> versionWarnings, Map<String, Set<String>> contracts, Set<String> exposed,
            Map<String, String> apiVersions, Map<String, String> hostApiVersions) {
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
        Set<String> discovered = new TreeSet<>();
        topology.idsOf(Mode.EXTERNAL).forEach(id -> {
            String prefix = MODES + "." + id + ".remote.";
            String url = binder.bind(prefix + "url", String.class).orElse("");
            String service = binder.bind(prefix + "service-id", String.class).orElse("");
            if (url.isBlank() == service.isBlank()) {
                problems.add(new Diagnostic(TopolithCode.TL0003, "external module '" + id
                        + "' must set exactly one of " + prefix + "url and " + prefix + "service-id.",
                        "Set exactly one of " + prefix + "url and " + prefix + "service-id."));
            }
            if (!service.isBlank() && url.isBlank()) {
                discovered.add(id);
                conflict(env, binder, prefix + "service-id", "spring.http.serviceclient." + id + ".base-url",
                        String.class, value -> normalizeUrl(ExternalClientProperties.serviceUrl(value) + "/" + id),
                        TopologyResolver::normalizeUrl, problems);
            } else if (!url.isBlank()) {
                conflict(env, binder, prefix + "url", "spring.http.serviceclient." + id + ".base-url", String.class,
                        value -> normalizeUrl(ExternalClientProperties.baseUrl(value, id)),
                        TopologyResolver::normalizeUrl, problems);
            }
            for (String key : List.of("connect-timeout", "read-timeout")) {
                conflict(env, binder, prefix + key, "spring.http.serviceclient." + id + "." + key, Duration.class,
                        value -> value, problems);
            }
        });
        Set<String> exposed = exposed(env, binder, topology, problems);
        Map<String, Set<String>> contracts = new TreeMap<>();
        found.apis().forEach(api -> contracts.computeIfAbsent(api.id(), key -> new LinkedHashSet<>())
                .addAll(api.provides()));
        found.modules().forEach(module -> contracts.computeIfAbsent(module.id(), key -> new LinkedHashSet<>())
                .addAll(module.provides()));
        ExternalContracts.checkDiscovery(discovered, classLoader, problems);
        ExternalContracts.check(topology.idsOf(Mode.EXTERNAL), contracts, classLoader, problems);
        Map<String, Set<String>> apiPackages = new TreeMap<>();
        found.apis().forEach(api -> apiPackages.computeIfAbsent(api.id(), key -> new TreeSet<>())
                .addAll(api.apiPackages()));
        found.modules().forEach(module -> apiPackages.computeIfAbsent(module.id(), key -> new TreeSet<>())
                .addAll(module.apiPackages()));
        Set<String> served = new TreeSet<>(topology.internal());
        served.addAll(topology.idsOf(Mode.EXTERNAL));
        ContractExceptions.check(served, contracts, apiPackages, classLoader, problems);
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
        Map<String, String> versions = new TreeMap<>();
        found.modules().forEach(module -> versions.put(module.id(), module.apiVersion()));
        Map<String, String> hostVersions = new TreeMap<>();
        found.apis().forEach(api -> hostVersions.put(api.id(), api.apiVersion()));
        return new Resolution(topology, found.importOwners(), found.rootOwners(), List.copyOf(warnings), contracts,
                Set.copyOf(exposed), Map.copyOf(versions), Map.copyOf(hostVersions));
    }

    /** The modules with {@code expose.enabled=true} (D-44); TL0003 if one is not internal or not a boolean. */
    private static Set<String> exposed(Environment env, Binder binder, Topology topology, List<Diagnostic> problems) {
        Set<String> exposed = new TreeSet<>();
        for (String id : topology.known()) {
            String key = MODES + "." + id + ".expose.enabled";
            try {
                if (binder.bind(key, Boolean.class).orElse(false)) {
                    if (topology.mode(id) == Mode.INTERNAL) {
                        exposed.add(id);
                    } else {
                        problems.add(new Diagnostic(TopolithCode.TL0003, "module '" + id + "' is exposed by " + key
                                + " but its mode is " + topology.mode(id).id() + ".", "Set topolith.modules." + id
                                + ".mode to internal, or remove " + key + "."));
                    }
                }
            } catch (BindException e) {
                problems.add(new Diagnostic(TopolithCode.TL0003, "invalid value '" + env.getProperty(key) + "' for "
                        + key + ".", "Set " + key + " to true or false."));
            }
        }
        return exposed;
    }

    private static <T> void conflict(Environment env, Binder binder, String source, String target, Class<T> type,
            Function<T, Object> normal, List<Diagnostic> problems) {
        conflict(env, binder, source, target, type, normal, normal, problems);
    }

    private static <T> void conflict(Environment env, Binder binder, String source, String target, Class<T> type,
            Function<T, Object> normal, Function<T, Object> targetNormal, List<Diagnostic> problems) {
        T left;
        T right;
        String key = source;
        try {
            left = binder.bind(source, type).orElse(null);
            key = target;
            right = binder.bind(target, type).orElse(null);
        } catch (BindException e) {
            problems.add(new Diagnostic(TopolithCode.TL0003, "invalid value '" + env.getProperty(key) + "' for " + key
                    + ".", "Set " + key + " to a valid " + (type == Duration.class ? "duration, such as 5s" : "value")
                    + "."));
            return;
        }
        if (left != null && right != null && !normal.apply(left).equals(targetNormal.apply(right))) {
            problems.add(new Diagnostic(TopolithCode.TL0003, "conflicting properties " + source + " and " + target
                    + ".", "Set both properties to the same value or remove one."));
        }
    }

    /** A URL with a lower-case scheme and host and no trailing slash, so equal endpoints compare equal. */
    static Object normalizeUrl(String url) {
        String trimmed = url.trim();
        try {
            URI uri = new URI(trimmed);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return trimmed;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (uri.getPort() >= 0 ? ":" + uri.getPort() : "") + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (URISyntaxException e) {
            return trimmed;
        }
    }

    static BigInteger major(String version) {
        return new BigInteger(version.split("[.-]", 2)[0]);
    }
}
