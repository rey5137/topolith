package io.github.rey5137.topolith.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/** The shared TL0006 check: every duplicated id, naming every source. */
final class Identity {
    private Identity() {
    }

    static <T> List<Diagnostic> duplicates(Collection<T> items, Function<T, String> id, Function<T, String> source,
            String kind) {
        Map<String, List<String>> sources = new TreeMap<>();
        for (T item : items) {
            sources.computeIfAbsent(id.apply(item), key -> new ArrayList<>()).add(source.apply(item));
        }
        List<Diagnostic> found = new ArrayList<>();
        sources.forEach((key, from) -> {
            if (from.size() > 1) {
                from.sort(Comparator.naturalOrder());
                found.add(new Diagnostic(TopolithCode.TL0006,
                        kind + " id '" + key + "' is declared " + from.size() + " times: " + String.join(", ", from)
                                + ".",
                        "Keep one " + kind + " per id: rename one, or remove the duplicate JAR from the classpath."));
            }
        });
        return found;
    }

    /**
     * The TL0017 check: two modules whose packages are equal or nested, and another module's api package that is
     * equal to or under a module's package. A module's own api packages are never compared with its own package.
     */
    static List<Diagnostic> nesting(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis) {
        Map<String, String> packages = new TreeMap<>();
        Map<String, Set<String>> apiPackages = new TreeMap<>();
        for (ModuleDescriptor module : modules) {
            packages.putIfAbsent(module.id(), module.packageName());
            apiPackages.computeIfAbsent(module.id(), key -> new TreeSet<>()).addAll(module.apiPackages());
        }
        for (ApiDescriptor api : apis) {
            apiPackages.computeIfAbsent(api.id(), key -> new TreeSet<>()).addAll(api.apiPackages());
        }
        List<Diagnostic> found = new ArrayList<>();
        for (Map.Entry<String, String> outer : packages.entrySet()) {
            for (Map.Entry<String, String> inner : packages.entrySet()) {
                if (outer.getKey().compareTo(inner.getKey()) < 0 && (within(outer.getValue(), inner.getValue())
                        || within(inner.getValue(), outer.getValue()))) {
                    found.add(new Diagnostic(TopolithCode.TL0017, "module packages nest: '" + outer.getKey()
                            + "' is in '" + outer.getValue() + "' and '" + inner.getKey() + "' is in '"
                            + inner.getValue() + "'.", "Give each module its own package tree, not inside another "
                            + "module's package."));
                }
            }
            apiPackages.forEach((id, owned) -> {
                if (!id.equals(outer.getKey())) {
                    owned.stream().filter(api -> within(api, outer.getValue())).forEach(api ->
                            found.add(new Diagnostic(TopolithCode.TL0017, "the api package '" + api + "' of module '"
                                    + id + "' is in the package '" + outer.getValue() + "' of module '"
                                    + outer.getKey() + "'.", "Move the api package out of the other module's "
                                    + "package tree, or move that module's root package.")));
                }
            });
        }
        return found;
    }

    /** Whether {@code inner} is {@code outer} or one of its subpackages. */
    private static boolean within(String inner, String outer) {
        return inner.equals(outer) || inner.startsWith(outer + ".");
    }
}
