package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import io.github.rey5137.topolith.spring.boot.internal.ListedContracts;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceSettings;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
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

    record MappingSettings(RemoteMapping remote) {
    }

    record RemoteMapping(Map<String, String> httpInterface) {
    }

    /** The topology with the discovery facts the filter and the persistence checks need. */
    record Resolution(Topology topology, Map<String, String> importOwners, Map<String, String> rootOwners,
            List<String> versionWarnings, Map<String, Set<String>> contracts, Set<String> exposed,
            Map<String, String> apiVersions, Map<String, String> hostApiVersions,
            Map<String, Map<String, String>> httpInterfaces, Map<String, Set<String>> idempotent,
            Map<String, Set<String>> listed) implements ListedContracts {
        @Override
        public Optional<String> definitionOwner(BeanDefinition definition) {
            return ModuleBeans.owner(definition, this);
        }

        @Override
        public Set<String> listed(String module) {
            return listed.getOrDefault(module, Set.of());
        }
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

    /** The topology the current environment asks for; refresh compares it with the running one (D-13). */
    static Topology requested(Environment environment, ClassLoader loader) {
        DescriptorDiscovery.Found found = DescriptorDiscovery.discover(loader);
        return Topology.resolve(found.modules(), found.apis(), declaredModes(environment), standalone(environment));
    }

    private static Map<String, String> declaredModes(Environment env) {
        Map<String, String> declared = new HashMap<>();
        try {
            Binder.get(env).bind(MODES, Bindable.mapOf(String.class, ModeOnly.class))
                    .ifBound(bound -> bound.forEach((id, value) -> declared.put(id, value.mode())));
        } catch (BindException e) {
            throw new TopolithException(notAMode(e));
        }
        return declared;
    }

    private static Resolution compute(Environment environment, ClassLoader loader) {
        Environment env = environment == null ? new StandardEnvironment() : environment;
        ClassLoader classLoader = loader == null ? Thread.currentThread().getContextClassLoader() : loader;
        DescriptorDiscovery.Found found = DescriptorDiscovery.discover(classLoader);
        Topology topology = Topology.resolve(found.modules(), found.apis(), declaredModes(env), standalone(env));
        List<Diagnostic> problems = new ArrayList<>();
        Binder binder = Binder.get(env);
        Set<String> discovered = new TreeSet<>();
        topology.idsOf(Mode.EXTERNAL).forEach(id -> {
            if (checkRemote(env, binder, id, problems)) {
                discovered.add(id);
            }
        });
        topology.idsOf(Mode.LOOPBACK).forEach(id -> checkLoopback(env, binder, id, problems));
        unknownModules(binder, topology, problems);
        LoopbackFaults.check(binder, topology, problems);
        OutgoingAuth.check(binder, topology, problems);
        ResilienceSettings.check(env, binder, topology, classLoader, problems);
        Set<String> exposed = exposed(env, binder, topology, problems);
        Map<String, Set<String>> contracts = new TreeMap<>();
        found.apis().forEach(api -> contracts.computeIfAbsent(api.id(), key -> new LinkedHashSet<>())
                .addAll(api.provides()));
        found.modules().forEach(module -> contracts.computeIfAbsent(module.id(), key -> new LinkedHashSet<>())
                .addAll(module.provides()));
        ExternalContracts.checkDiscovery(discovered, classLoader, problems);
        Map<String, Map<String, String>> mappings = httpInterfaces(binder, topology, contracts, problems);
        for (Mode mode : List.of(Mode.EXTERNAL, Mode.LOOPBACK)) {
            ExternalContracts.check(mode, topology.idsOf(mode), contracts, mappings, classLoader, problems);
        }
        IdempotencyCheck.check(topology, found.modules(), found.apis(), mappings, classLoader, problems);
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
            if (!topology.internal().contains(api.id())) {
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
        Map<String, Set<String>> idempotent = new TreeMap<>();
        // the effective descriptor only (R-DESC-05): the API descriptor of an external module, the module descriptor of
        // an internal or loopback one; a union would let a skewed jar widen what is retried
        found.apis().stream().filter(api -> topology.idsOf(Mode.EXTERNAL).contains(api.id()))
                .forEach(api -> idempotent.computeIfAbsent(api.id(), key -> new TreeSet<>()).addAll(api.idempotent()));
        found.modules().stream().filter(module -> topology.internal().contains(module.id()))
                .forEach(module -> idempotent.computeIfAbsent(module.id(), key -> new TreeSet<>())
                        .addAll(module.idempotent()));
        Map<String, String> versions = new TreeMap<>();
        found.modules().forEach(module -> versions.put(module.id(), module.apiVersion()));
        Map<String, String> hostVersions = new TreeMap<>();
        found.apis().forEach(api -> hostVersions.put(api.id(), api.apiVersion()));
        // the contracts an internal or loopback module's declaration lists (D-110), read only for those (INV-2)
        Map<String, Set<String>> listed = new TreeMap<>();
        found.modules().stream().filter(module -> topology.internal().contains(module.id()))
                .forEach(module -> listed.put(module.id(), ModuleDeclarations.listed(classLoader, module)));
        return new Resolution(topology, found.importOwners(), found.rootOwners(), List.copyOf(warnings), contracts,
                Set.copyOf(exposed), Map.copyOf(versions), Map.copyOf(hostVersions), mappings, idempotent,
                Map.copyOf(listed));
    }

    /**
     * The endpoint rules of external module {@code id} (TL0003); true when it uses a service id. Startup and refresh
     * (D-50) share it.
     */
    static boolean checkRemote(Environment env, Binder binder, String id, List<Diagnostic> problems) {
        String prefix = MODES + "." + id + ".remote.";
        String url = binder.bind(prefix + "url", String.class).orElse("");
        String service = binder.bind(prefix + "service-id", String.class).orElse("");
        if (url.isBlank() == service.isBlank()) {
            problems.add(new Diagnostic(TopolithCode.TL0003, "external module '" + id
                    + "' must set exactly one of " + prefix + "url and " + prefix + "service-id.",
                    "Set exactly one of " + prefix + "url and " + prefix + "service-id."));
        }
        boolean discovered = false;
        if (!service.isBlank() && url.isBlank()) {
            discovered = true;
            conflict(env, binder, prefix + "service-id", ExternalClientProperties.groupPrefix(id) + "base-url",
                    String.class, value -> normalizeUrl(ExternalClientProperties.serviceUrl(value) + "/" + id),
                    TopologyResolver::normalizeUrl, problems);
        } else if (!url.isBlank()) {
            conflict(env, binder, prefix + "url", ExternalClientProperties.groupPrefix(id) + "base-url", String.class,
                    value -> normalizeUrl(ExternalClientProperties.baseUrl(value, id)),
                    TopologyResolver::normalizeUrl, problems);
        }
        timeoutConflicts(env, binder, id, problems);
        return discovered;
    }

    private static void timeoutConflicts(Environment env, Binder binder, String id, List<Diagnostic> problems) {
        for (String key : List.of("connect-timeout", "read-timeout")) {
            conflict(env, binder, MODES + "." + id + ".remote." + key, ExternalClientProperties.groupPrefix(id) + key,
                    Duration.class, value -> value, problems);
        }
    }

    /**
     * The settings rules of loopback module {@code id} (R-MODE-06, TL0003): the client always targets the host's own
     * server, so a non-blank {@code remote.url} or {@code remote.service-id} and an explicit Boot {@code base-url}
     * contradict the mode. Its timeouts follow the external rules.
     */
    private static void checkLoopback(Environment env, Binder binder, String id, List<Diagnostic> problems) {
        String prefix = MODES + "." + id + ".remote.";
        for (String key : List.of(prefix + "url", prefix + "service-id")) {
            if (!binder.bind(key, String.class).orElse("").isBlank()) {
                problems.add(new Diagnostic(TopolithCode.TL0003, "loopback module '" + id + "' sets " + key
                        + ", but a loopback module calls the host's own server.", "Remove " + key + "."));
            }
        }
        String base = ExternalClientProperties.groupPrefix(id) + "base-url";
        if (userSets(env, binder, base)) {
            problems.add(new Diagnostic(TopolithCode.TL0003, "loopback module '" + id + "' sets " + base
                    + ", but a loopback module calls the host's own server.", "Remove " + base + "."));
        }
        timeoutConflicts(env, binder, id, problems);
    }

    /** Whether a property source other than Topolith's own mapped one (the loopback placeholder) sets {@code key}. */
    private static boolean userSets(Environment env, Binder binder, String key) {
        ConfigurationPropertyName name = ConfigurationPropertyName.ofIfValid(key);
        if (!(env instanceof ConfigurableEnvironment) || name == null) {
            return binder.bind(key, String.class).isBound();
        }
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(env)) {
            if (!(source.getUnderlyingSource() instanceof PropertySource<?> underlying
                    && underlying.getName().equals(ExternalClientProperties.SOURCE_NAME))
                    && source.getConfigurationProperty(name) != null) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Map<String, String>> httpInterfaces(Binder binder, Topology topology,
            Map<String, Set<String>> contracts, List<Diagnostic> problems) {
        Map<String, Map<String, String>> result = new TreeMap<>();
        try {
            binder.bind(MODES, Bindable.mapOf(String.class, MappingSettings.class)).ifBound(modules ->
                    modules.forEach((id, settings) -> {
                        if (settings.remote() == null || settings.remote().httpInterface() == null) {
                            return;
                        }
                        settings.remote().httpInterface().forEach((contract, type) -> {
                            String property = MODES + "." + id + ".remote.http-interface." + contract;
                            String reason = null;
                            if (!topology.known().contains(id)) {
                                reason = "module '" + id + "' is unknown, not external";
                            } else if (topology.mode(id) != Mode.EXTERNAL && topology.mode(id) != Mode.LOOPBACK) {
                                reason = "module '" + id + "' has mode " + topology.mode(id).id()
                                        + ", not external or loopback";
                            } else if (!validClassName(contract)) {
                                reason = "malformed contract key '" + contract + "'";
                            } else if (!contracts.getOrDefault(id, Set.of()).contains(contract)) {
                                reason = "contract '" + contract + "' is not in provides: "
                                        + contracts.getOrDefault(id, Set.of());
                                if (contract.equals(contract.toLowerCase(Locale.ROOT))) {
                                    reason += "; environment variables cannot preserve case-sensitive mapping keys";
                                }
                            }
                            if (reason != null) {
                                problems.add(new Diagnostic(TopolithCode.TL0003, "invalid mapping " + property
                                        + ": " + reason + ".", "Map a provided contract of an external or "
                                        + "loopback module."));
                            } else {
                                result.computeIfAbsent(id, key -> new TreeMap<>()).put(contract, type);
                            }
                        });
                    }));
        } catch (BindException e) {
            BindException deepest = e;
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof BindException bind) {
                    deepest = bind;
                }
            }
            String name = deepest.getName() == null ? MODES : deepest.getName().toString();
            problems.add(new Diagnostic(TopolithCode.TL0003, "invalid mapping property '" + name + "'.",
                    "Set topolith.modules.<id>.remote.http-interface.<contract FQN>=<interface FQN>."));
        }
        return result;
    }

    private static boolean validClassName(String name) {
        String[] parts = name.split("\\.", -1);
        if (parts.length < 2) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
                return false;
            }
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * TL0003 for each {@code topolith.modules.<id>.expose.*} that names no known module, so a typo cannot silently leave
     * the real module unexposed. Other settings of an absent module stay allowed (a shared configuration): a
     * {@code mode} is TL0016 or TL0002 (raised by the topology), a {@code remote.http-interface} mapping is reported by
     * {@link #httpInterfaces}, and a mistyped {@code remote.*} leaves the real external module without an endpoint
     * (TL0003).
     */
    private static void unknownModules(Binder binder, Topology topology, List<Diagnostic> problems) {
        Set<String> unknown = new TreeSet<>();
        try {
            Set<String> compact = new HashSet<>();
            topology.known().forEach(id -> compact.add(id.replace("-", "")));
            binder.bind(MODES, Bindable.mapOf(String.class, Object.class)).ifBound(bound -> bound.forEach((id, value) -> {
                // an environment variable cannot carry a dash: ORDERSERVICE binds as "orderservice" (D-50 known limit)
                if (!topology.known().contains(id) && !compact.contains(id.replace("-", "")) && exposes(value)) {
                    unknown.add(id);
                }
            }));
        } catch (BindException e) {
            return;
        }
        unknown.forEach(id -> problems.add(new Diagnostic(TopolithCode.TL0003, "module '" + id
                + "' is unknown, but " + MODES + "." + id + ".* is set.", "Correct the module id, or remove "
                + MODES + "." + id + ".*.")));
    }

    /** Whether an id's settings carry {@code expose.*}, which would otherwise be dropped without a trace. */
    private static boolean exposes(Object value) {
        return value instanceof Map<?, ?> settings && settings.containsKey("expose");
    }

    /**
     * The modules whose routes are served: those with {@code expose.enabled=true} (D-44) and every loopback module,
     * whose routes are always mapped (D-76). TL0003 if an exposed module is neither internal nor loopback, if a
     * loopback module sets {@code expose.enabled=false}, or if the value is not a boolean.
     */
    private static Set<String> exposed(Environment env, Binder binder, Topology topology, List<Diagnostic> problems) {
        Set<String> exposed = new TreeSet<>();
        for (String id : topology.known()) {
            String key = MODES + "." + id + ".expose.enabled";
            try {
                if (topology.mode(id) == Mode.LOOPBACK) {
                    if (binder.bind(key, Boolean.class).orElse(true)) {
                        exposed.add(id);
                    } else {
                        problems.add(new Diagnostic(TopolithCode.TL0003, "loopback module '" + id + "' sets " + key
                                + "=false, but a loopback module's routes are always served.", "Remove " + key
                                + ", or set topolith.modules." + id + ".mode to internal."));
                    }
                } else if (binder.bind(key, Boolean.class).orElse(false)) {
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
