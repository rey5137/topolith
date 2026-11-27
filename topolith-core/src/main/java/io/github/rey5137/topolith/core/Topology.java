package io.github.rey5137.topolith.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The resolved topology of a host: every known module with its mode (D-31). A module is known when a module descriptor
 * or an API descriptor carries its id. Resolution reports each stage completely and stops after the first stage that
 * fails (D-28): identity (TL0006, TL0017), graph (TL0008), modes (TL0001, TL0016, TL0002, TL0025), requirements
 * (TL0004), providers (TL0005, TL0022). The host then checks external endpoints (TL0003).
 */
@Incubating
public final class Topology {
    private final ModuleGraph graph;
    private final Map<String, Mode> modes;
    private final Map<String, String> modulePackages;
    private final Set<String> apiPackages;

    private Topology(ModuleGraph graph, Map<String, Mode> modes, Set<String> apiPackages) {
        this.graph = graph;
        this.modes = Collections.unmodifiableMap(modes);
        Map<String, String> packages = new TreeMap<>();
        graph.modules().forEach(module -> packages.put(module.id(), module.packageName()));
        this.modulePackages = Collections.unmodifiableMap(packages);
        this.apiPackages = Collections.unmodifiableSet(apiPackages);
    }

    /**
     * Resolves the modes.
     *
     * @param modules    every module descriptor on the classpath
     * @param apis       every API descriptor on the classpath
     * @param declared   the configured mode value by module id; a null value means the key is absent
     * @param standalone the id of the standalone {@code *-service} module whose missing mode defaults to internal,
     *                   or null
     * @throws TopolithException with every diagnostic of the first failing stage
     */
    public static Topology resolve(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis,
            Map<String, String> declared, String standalone) {
        identity(modules, apis);
        ModuleGraph graph = ModuleGraph.of(modules);
        Set<String> known = new TreeSet<>();
        graph.modules().forEach(module -> known.add(module.id()));
        apis.forEach(api -> known.add(api.id()));
        Map<String, Mode> modes = resolveModes(graph, known, new TreeMap<>(declared), standalone);
        checkContracts(graph, modes);
        Set<String> packages = new TreeSet<>();
        graph.modules().forEach(module -> packages.addAll(module.apiPackages()));
        apis.forEach(api -> packages.addAll(api.apiPackages()));
        Topology topology = new Topology(graph, new LinkedHashMap<>(modes), packages);
        topology.checkRequirements();
        checkProviders(modules, apis, modes);
        return topology;
    }

    /** A module without api packages has nothing to call remotely: external or loopback fails (D-115, TL0003). */
    private static void checkContracts(ModuleGraph graph, Map<String, Mode> modes) {
        List<Diagnostic> found = new ArrayList<>();
        graph.modules().forEach(module -> {
            Mode mode = modes.get(module.id());
            if (module.apiPackages().isEmpty() && (mode == Mode.EXTERNAL || mode == Mode.LOOPBACK)) {
                found.add(new Diagnostic(TopolithCode.TL0003, "module '" + module.id() + "' has no api packages, so "
                        + "it cannot be " + mode.name().toLowerCase(Locale.ROOT) + ".",
                        "Set topolith.modules." + module.id() + ".mode to internal or none."));
            }
        });
        if (!found.isEmpty()) {
            throw new TopolithException(found);
        }
    }

    private static void checkProviders(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis,
            Map<String, Mode> modes) {
        Map<String, ModuleDescriptor> implementations = new TreeMap<>();
        modules.forEach(module -> implementations.put(module.id(), module));
        Map<String, ApiDescriptor> interfaces = new TreeMap<>();
        apis.forEach(api -> interfaces.put(api.id(), api));
        List<Diagnostic> found = new ArrayList<>();
        Map<String, Set<String>> providers = new TreeMap<>();
        modes.forEach((id, mode) -> {
            if (mode == Mode.NONE) {
                return;
            }
            ModuleDescriptor module = implementations.get(id);
            ApiDescriptor api = interfaces.get(id);
            if (activated(mode) && module != null && api != null && !module.provides().equals(api.provides())) {
                Set<String> onlyModule = new TreeSet<>(module.provides());
                onlyModule.removeAll(api.provides());
                Set<String> onlyApi = new TreeSet<>(api.provides());
                onlyApi.removeAll(module.provides());
                found.add(new Diagnostic(TopolithCode.TL0022, "module '" + id + "' has different contracts: "
                        + "only in module " + onlyModule + "; only in API " + onlyApi + ".",
                        "Align the API JAR and implementation JAR versions."));
            }
            Set<String> contracts = new TreeSet<>();
            if (module != null && (activated(mode) || api == null)) {
                contracts.addAll(module.provides());
            }
            if (api != null) {
                contracts.addAll(api.provides());
            }
            contracts.forEach(contract -> providers.computeIfAbsent(contract, key -> new TreeSet<>()).add(id));
        });
        providers.forEach((contract, ids) -> {
            if (ids.size() > 1) {
                found.add(new Diagnostic(TopolithCode.TL0005, "contract '" + contract + "' has providers " + ids + ".",
                        "Remove the duplicate provider or set all but one provider to none."));
            }
        });
        if (!found.isEmpty()) {
            throw new TopolithException(found);
        }
    }

    private static void identity(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis) {
        List<Diagnostic> found = new ArrayList<>(Identity.duplicates(modules, ModuleDescriptor::id,
                ModuleDescriptor::source, "module descriptor"));
        found.addAll(Identity.duplicates(apis, ApiDescriptor::id, ApiDescriptor::source, "API descriptor"));
        found.addAll(Identity.nesting(modules, apis));
        if (!found.isEmpty()) {
            throw new TopolithException(found);
        }
    }

    private static Map<String, Mode> resolveModes(ModuleGraph graph, Set<String> known, Map<String, String> declared,
            String standalone) {
        List<Diagnostic> found = new ArrayList<>();
        Map<String, Mode> modes = new TreeMap<>();
        if (standalone != null && graph.module(standalone).isEmpty()) {
            found.add(new Diagnostic(TopolithCode.TL0002, "standalone module '" + standalone
                    + "' has no module descriptor (META-INF/topolith/modules/" + standalone + ".properties).",
                    "Add the module's implementation to this service, or correct topolith.standalone."));
        }
        for (String id : known) {
            String value = declared.get(id);
            Mode mode;
            if (value == null) {
                if (!id.equals(standalone)) {
                    found.add(new Diagnostic(TopolithCode.TL0001, "no mode is declared for module '" + id + "'.",
                            "Set topolith.modules." + id + ".mode to internal, external, loopback or none."));
                    continue;
                }
                mode = Mode.INTERNAL;
            } else {
                Optional<Mode> parsed = Mode.parse(value);
                if (parsed.isEmpty()) {
                    found.add(new Diagnostic(TopolithCode.TL0001, "module '" + id + "' has the invalid mode '"
                            + value + "'.", "Set topolith.modules." + id
                            + ".mode to internal, external, loopback or none."));
                    continue;
                }
                mode = parsed.get();
            }
            if (activated(mode) && graph.module(id).isEmpty() && !id.equals(standalone)) {
                found.add(missingImplementation(id, mode));
            }
            if (activated(mode) && standalone != null && !id.equals(standalone) && graph.module(id).isPresent()) {
                found.add(new Diagnostic(TopolithCode.TL0025, "topolith.standalone is '" + standalone
                        + "' but module '" + id + "' is also internal: a standalone service hosts only its own module.",
                        "Remove topolith.standalone and declare every mode, or set topolith.modules." + id
                                + ".mode to external or none."));
            }
            modes.put(id, mode);
        }
        declared.forEach((id, value) -> {
            // a standalone id with no module descriptor is already reported above
            if (value != null && !known.contains(id) && !id.equals(standalone)) {
                Optional<Mode> parsed = Mode.parse(value).filter(Topology::activated);
                if (parsed.isPresent()) {
                    found.add(missingImplementation(id, parsed.get()));
                } else {
                    found.add(new Diagnostic(TopolithCode.TL0016, "a mode is declared for module '" + id
                            + "', which has no module or API descriptor on the classpath.",
                            "Correct the id in topolith.modules." + id + ".mode, or remove it, or add the module's "
                                    + "JAR."));
                }
            }
        });
        if (!found.isEmpty()) {
            throw new TopolithException(found);
        }
        return modes;
    }

    private static Diagnostic missingImplementation(String id, Mode mode) {
        return new Diagnostic(TopolithCode.TL0002, "module '" + id + "' is " + mode.id()
                + " but has no implementation: META-INF/topolith/modules/" + id + ".properties is not on the classpath.",
                "Add the JAR that carries META-INF/topolith/modules/" + id + ".properties, or set "
                        + "topolith.modules." + id + ".mode to external or none.");
    }

    /** Internal for activation (D-75): the module's implementation runs in the host. */
    private static boolean activated(Mode mode) {
        return mode == Mode.INTERNAL || mode == Mode.LOOPBACK;
    }

    // D-21: only internal (and loopback, D-75) modules are checked; an external module counts as available.
    private void checkRequirements() {
        Set<String> available = new LinkedHashSet<>(internal());
        modes.forEach((id, mode) -> {
            if (mode == Mode.EXTERNAL) {
                available.add(id);
            }
        });
        List<Diagnostic> found = new ArrayList<>();
        graph.unmetRequirements(available).forEach((id, missing) -> {
            if (!activated(modes.get(id))) {
                return;
            }
            for (String required : missing) {
                String state = modes.containsKey(required) ? "its mode is " + modes.get(required).id()
                        : "it is not on the classpath";
                found.add(new Diagnostic(TopolithCode.TL0004, modes.get(id).id() + " module '" + id + "' requires '"
                        + required + "', which is not available: " + state + ".",
                        "Set topolith.modules." + required + ".mode to internal or external, or set "
                                + "topolith.modules." + id + ".mode to external or none."));
            }
        });
        if (!found.isEmpty()) {
            throw new TopolithException(found);
        }
    }

    /** The module descriptors and their edges. */
    public ModuleGraph graph() {
        return graph;
    }

    /** Every known module id, sorted. */
    public Set<String> known() {
        return modes.keySet();
    }

    /** The mode of a known module; throws {@link IllegalArgumentException} for an unknown id. */
    public Mode mode(String id) {
        Mode mode = modes.get(id);
        if (mode == null) {
            throw new IllegalArgumentException("unknown module '" + id + "'");
        }
        return mode;
    }

    /**
     * The ids of the modules that are internal for activation, sorted: {@code internal} and {@code loopback} modules,
     * whose implementation runs in the host (D-75). {@link #idsOf(Mode)} tells the two apart.
     */
    public Set<String> internal() {
        Set<String> ids = new TreeSet<>(idsOf(Mode.INTERNAL));
        ids.addAll(idsOf(Mode.LOOPBACK));
        return Collections.unmodifiableSet(ids);
    }

    /** The ids of the modules with the given mode, sorted. */
    public Set<String> idsOf(Mode mode) {
        Set<String> ids = new TreeSet<>();
        modes.forEach((id, value) -> {
            if (value == mode) {
                ids.add(id);
            }
        });
        return Collections.unmodifiableSet(ids);
    }

    /** The root package of every module that has a module descriptor, by id. */
    public Map<String, String> modulePackages() {
        return modulePackages;
    }

    /** Every known api package, from module and API descriptors, sorted. */
    public Set<String> apiPackages() {
        return apiPackages;
    }

    /**
     * The module whose root package holds the class, by the package rule only; empty for a class in no module's
     * package. Module packages never nest, so at most one module matches.
     */
    public Optional<String> ownerOfClass(String className) {
        for (Map.Entry<String, String> entry : modulePackages.entrySet()) {
            if (className.startsWith(entry.getValue() + ".")) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "Topology" + modes;
    }
}
