package io.github.rey5137.topolith.spring.boot;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import io.github.rey5137.topolith.core.Incubating;

/**
 * The URL prefixes this host serves for its exposed modules, {@code /<id>} each (R-EXT-05, D-44). A host that secures
 * them reads {@link #patterns()}, or, with Spring Security present, injects {@link ExposedRequestMatcher} into its own
 * {@code SecurityFilterChain}; Topolith registers no chain itself (INV-4, D-46). Empty when no module is exposed.
 */
@Incubating
public final class ExposedEndpoints {
    private final Set<String> moduleIds;

    ExposedEndpoints(Collection<String> moduleIds) {
        this.moduleIds = Set.copyOf(new TreeSet<>(moduleIds));
    }

    /** The ids of the exposed modules, sorted. */
    public Set<String> moduleIds() {
        return new TreeSet<>(moduleIds);
    }

    /** One prefix per exposed module, such as {@code /user}, sorted. */
    public Set<String> prefixes() {
        return moduleIds.stream().map(id -> "/" + id).collect(Collectors.toCollection(TreeSet::new));
    }

    /** One path pattern per exposed module covering its whole prefix, such as {@code /user/**}, sorted. */
    public Set<String> patterns() {
        return moduleIds.stream().map(id -> "/" + id + "/**").collect(Collectors.toCollection(TreeSet::new));
    }

    public boolean isEmpty() {
        return moduleIds.isEmpty();
    }
}
