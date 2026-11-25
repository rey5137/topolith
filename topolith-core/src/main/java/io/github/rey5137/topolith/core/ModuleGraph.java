package io.github.rey5137.topolith.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The module descriptors with their {@code requires} and {@code optional} edges. A required edge to a module with
 * no descriptor is allowed: unmet requirements are a query ({@link #unmetRequirements}), and cycles are checked over
 * every descriptor present, whatever the modes.
 */
@Incubating
public final class ModuleGraph {
    private final Map<String, ModuleDescriptor> modules;

    private ModuleGraph(Map<String, ModuleDescriptor> modules) {
        this.modules = modules;
    }

    /**
     * Builds the graph. Stage 2: duplicate ids fail with one TL0006 per id. Stage 3, only if stage 2 passed: one
     * TL0008 per cycle group of {@code requires} edges ({@code optional} edges never form a failing cycle).
     */
    public static ModuleGraph of(Collection<ModuleDescriptor> descriptors) {
        List<Diagnostic> duplicates = Identity.duplicates(descriptors, ModuleDescriptor::id, ModuleDescriptor::source,
                "module descriptor");
        if (!duplicates.isEmpty()) {
            throw new TopolithException(duplicates);
        }
        Map<String, ModuleDescriptor> byId = new TreeMap<>();
        descriptors.forEach(descriptor -> byId.put(descriptor.id(), descriptor));
        ModuleGraph graph = new ModuleGraph(Collections.unmodifiableMap(byId));
        List<Diagnostic> cycles = graph.cycles();
        if (!cycles.isEmpty()) {
            throw new TopolithException(cycles);
        }
        return graph;
    }

    /** The descriptors, ordered by id. */
    public Collection<ModuleDescriptor> modules() {
        return modules.values();
    }

    public Optional<ModuleDescriptor> module(String id) {
        return Optional.ofNullable(modules.get(id));
    }

    /** The required ids of a module, or none for an unknown id. */
    public Set<String> requires(String id) {
        return module(id).map(ModuleDescriptor::requires).orElse(Set.of());
    }

    /** The optional ids of a module, or none for an unknown id. */
    public Set<String> optional(String id) {
        return module(id).map(ModuleDescriptor::optional).orElse(Set.of());
    }

    /**
     * For each active module known to the graph, the required ids that are not active; modules with nothing unmet
     * are left out. Never throws. An absent optional dependency is never unmet.
     */
    public Map<String, Set<String>> unmetRequirements(Set<String> active) {
        Map<String, Set<String>> unmet = new TreeMap<>();
        for (String id : new TreeSet<>(active)) {
            ModuleDescriptor module = modules.get(id);
            if (module == null) {
                continue;
            }
            Set<String> missing = new LinkedHashSet<>(module.requires());
            missing.removeAll(active);
            if (!missing.isEmpty()) {
                unmet.put(id, Collections.unmodifiableSet(missing));
            }
        }
        return Collections.unmodifiableMap(unmet);
    }

    // Tarjan's strongly connected components over required edges between present modules.
    private List<Diagnostic> cycles() {
        Tarjan tarjan = new Tarjan();
        modules.keySet().forEach(id -> {
            if (!tarjan.index.containsKey(id)) {
                tarjan.visit(id);
            }
        });
        List<Diagnostic> found = new ArrayList<>();
        tarjan.components.sort(Comparator.comparing(component -> component.first()));
        for (TreeSet<String> component : tarjan.components) {
            String start = component.first();
            found.add(new Diagnostic(TopolithCode.TL0008,
                    "required modules form a cycle: " + String.join(" -> ", path(start, component)) + ".",
                    "Break the cycle: remove one 'requires' edge, or make it 'optional'."));
        }
        return found;
    }

    private List<String> path(String start, Set<String> component) {
        Map<String, String> parent = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        parent.put(start, null);
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : new TreeSet<>(requires(current))) {
                if (!component.contains(next)) {
                    continue;
                }
                if (next.equals(start)) {
                    List<String> path = new ArrayList<>();
                    for (String step = current; step != null; step = parent.get(step)) {
                        path.add(0, step);
                    }
                    path.add(start);
                    return path;
                }
                if (parent.putIfAbsent(next, current) == null) {
                    queue.add(next);
                }
            }
        }
        throw new IllegalStateException("no cycle through " + start);
    }

    private final class Tarjan {
        final Map<String, Integer> index = new HashMap<>();
        final Map<String, Integer> low = new HashMap<>();
        final Deque<String> stack = new ArrayDeque<>();
        final Set<String> onStack = new HashSet<>();
        final List<TreeSet<String>> components = new ArrayList<>();

        void visit(String id) {
            index.put(id, index.size());
            low.put(id, index.get(id));
            stack.push(id);
            onStack.add(id);
            for (String next : new TreeSet<>(requires(id))) {
                if (!modules.containsKey(next)) {
                    continue;
                }
                if (!index.containsKey(next)) {
                    visit(next);
                    low.put(id, Math.min(low.get(id), low.get(next)));
                } else if (onStack.contains(next)) {
                    low.put(id, Math.min(low.get(id), index.get(next)));
                }
            }
            if (low.get(id).equals(index.get(id))) {
                TreeSet<String> component = new TreeSet<>();
                String member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(id));
                if (component.size() > 1 || requires(id).contains(id)) {
                    components.add(component);
                }
            }
        }
    }
}
