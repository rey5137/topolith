package io.github.rey5137.topolith.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
}
