package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.rey5137.topolith.contract.Idempotent;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.core.TopolithCode;

/**
 * TL0040 (R-DESC-05, D-100): the {@code idempotent} set of an internal, loopback or external module's effective
 * descriptor must match the {@code @Idempotent} annotations of its loaded contracts, with the processor's rule read by
 * reflection. A {@code none} module is never checked (INV-2).
 */
final class IdempotencyCheck {
    private static final String ACTION = "Rebuild the API and module artifacts with the Topolith processor, and keep "
            + "@Idempotent on abstract contract methods or the contract interface, with every overload agreeing.";

    private IdempotencyCheck() {
    }

    /** Adds a TL0040 diagnostic for every difference or misplaced {@code @Idempotent}, naming module and contract. */
    static void check(Topology topology, Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis,
            Map<String, Map<String, String>> mappings, ClassLoader loader, List<Diagnostic> problems) {
        Map<String, ModuleDescriptor> moduleById = new TreeMap<>();
        modules.forEach(module -> moduleById.put(module.id(), module));
        Map<String, ApiDescriptor> apiById = new TreeMap<>();
        apis.forEach(api -> apiById.put(api.id(), api));
        for (Mode mode : List.of(Mode.INTERNAL, Mode.LOOPBACK, Mode.EXTERNAL)) {
            for (String id : topology.idsOf(mode)) {
                ModuleDescriptor module = moduleById.get(id);
                ApiDescriptor api = apiById.get(id);
                ApiDescriptor effective = mode == Mode.EXTERNAL ? api : module == null ? null : module.api();
                if (effective == null) {
                    continue;
                }
                String label = mode.id() + " module '" + id + "'";
                if (mode != Mode.EXTERNAL && api != null && !api.idempotent().equals(module.idempotent())) {
                    problems.add(new Diagnostic(TopolithCode.TL0040, label + " declares idempotent "
                            + module.idempotent() + " in " + module.source() + " but " + api.idempotent() + " in "
                            + api.source() + ".", ACTION));
                }
                for (String contract : effective.provides()) {
                    checkContract(label, effective, contract, loader, problems);
                }
                mappings.getOrDefault(id, Map.of()).forEach((contract, http) ->
                        checkHttpInterface(label, contract, http, loader, problems));
            }
        }
    }

    /**
     * Reflection resolves every method's parameter and return types, so a contract whose types are not all on the
     * classpath is skipped here: TL0026 or the module's own checks report it, and a raw {@link LinkageError} would
     * hide those diagnostics.
     */
    private static void checkContract(String label, ApiDescriptor descriptor, String contract, ClassLoader loader,
            List<Diagnostic> problems) {
        Class<?> type = load(contract, loader);
        if (type == null || !type.isInterface()) {
            return;
        }
        List<Diagnostic> found = new ArrayList<>();
        try {
            compare(label, descriptor, contract, type, found);
        } catch (LinkageError e) {
            return;
        }
        problems.addAll(found);
    }

    private static void compare(String label, ApiDescriptor descriptor, String contract, Class<?> type,
            List<Diagnostic> problems) {
        Set<String> declared = new TreeSet<>();
        descriptor.idempotent().stream().filter(entry -> entry.startsWith(contract + "#")).forEach(declared::add);
        Set<String> annotated = new TreeSet<>();
        boolean whole = type.isAnnotationPresent(Idempotent.class);
        Map<String, Set<Boolean>> byName = new TreeMap<>();
        for (Method method : exposed(type)) {
            if (method.isSynthetic() || objectMethod(method)) {
                continue;
            }
            if (method.isDefault() && method.isAnnotationPresent(Idempotent.class)) {
                problems.add(new Diagnostic(TopolithCode.TL0040, label + " contract " + contract
                        + " has @Idempotent on default method " + method.getName() + ", which never makes a call.",
                        ACTION));
            } else if (Modifier.isAbstract(method.getModifiers())) {
                boolean idempotent = whole || method.isAnnotationPresent(Idempotent.class)
                        || method.getDeclaringClass().isAnnotationPresent(Idempotent.class);
                byName.computeIfAbsent(method.getName(), key -> new TreeSet<>()).add(idempotent);
            }
        }
        byName.forEach((name, flags) -> {
            if (flags.size() > 1) {
                problems.add(new Diagnostic(TopolithCode.TL0040, label + " contract " + contract
                        + " has overloads of " + name + " that disagree on @Idempotent.", ACTION));
            } else if (flags.contains(true)) {
                annotated.add(contract + "#" + name);
            }
        });
        if (!declared.equals(annotated)) {
            problems.add(new Diagnostic(TopolithCode.TL0040, label + " contract " + contract + " is declared "
                    + "idempotent for " + declared + " in " + descriptor.source() + " but its @Idempotent "
                    + "annotations give " + annotated + ".", ACTION));
        }
    }

    private static void checkHttpInterface(String label, String contract, String http, ClassLoader loader,
            List<Diagnostic> problems) {
        if (http.equals(contract)) {
            return; // the contract itself is not a separate HTTP interface (D-49)
        }
        Class<?> type = load(http, loader);
        Class<?> contractType = load(contract, loader);
        if (type == null) {
            return;
        }
        Set<String> carriers = new TreeSet<>();
        try {
            List<Class<?>> pending = new ArrayList<>(List.of(type));
            Set<Class<?>> seen = new HashSet<>();
            while (!pending.isEmpty()) {
                Class<?> current = pending.remove(pending.size() - 1);
                if (!seen.add(current) || contractType != null && current.isAssignableFrom(contractType)) {
                    continue; // the contract and its supertypes are checked as the contract
                }
                if (current.isAnnotationPresent(Idempotent.class) || Arrays.stream(current.getDeclaredMethods())
                        .anyMatch(method -> method.isAnnotationPresent(Idempotent.class))) {
                    carriers.add(current.getName());
                }
                pending.addAll(Arrays.asList(current.getInterfaces()));
            }
        } catch (LinkageError e) {
            return;
        }
        if (!carriers.isEmpty()) {
            problems.add(new Diagnostic(TopolithCode.TL0040, label + " maps contract " + contract
                    + " to HTTP interface " + http + ", which carries @Idempotent"
                    + (carriers.equals(Set.of(http)) ? "" : " on " + carriers) + ".",
                    "Put @Idempotent on the contract " + contract + " in the API, not on the HTTP interface."));
        }
    }

    private static Class<?> load(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null; // TL0026 or the module's own checks report a contract that cannot be loaded
        }
    }

    /**
     * The methods the interface exposes: its own declarations, then each superinterface method it inherits, one
     * declaration per signature (the one it overrides wins, JLS 9.4.1; a bridge stands for a generic override).
     * Static and private methods are not inherited.
     */
    private static List<Method> exposed(Class<?> type) {
        List<Method> result = new ArrayList<>(Arrays.asList(type.getDeclaredMethods()));
        List<Method> inherited = new ArrayList<>();
        for (Class<?> parent : type.getInterfaces()) {
            for (Method method : exposed(parent)) {
                if (!Modifier.isStatic(method.getModifiers()) && !Modifier.isPrivate(method.getModifiers())
                        && !inherited.contains(method)) {
                    inherited.add(method);
                }
            }
        }
        for (Method method : inherited) {
            boolean overridden = result.stream().anyMatch(own -> own.getDeclaringClass() == type
                    && sameSignature(own, method))
                    || inherited.stream().anyMatch(other -> other.getDeclaringClass() != method.getDeclaringClass()
                            && sameSignature(other, method)
                            && method.getDeclaringClass().isAssignableFrom(other.getDeclaringClass()));
            if (!overridden) {
                result.add(method);
            }
        }
        return result;
    }

    private static boolean sameSignature(Method first, Method second) {
        return first.getName().equals(second.getName())
                && Arrays.equals(first.getParameterTypes(), second.getParameterTypes());
    }

    /** A redeclaration of a public {@code java.lang.Object} method, which a contract never exposes as a call. */
    private static boolean objectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
