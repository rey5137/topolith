package io.github.rey5137.topolith.spring.boot;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The spellings of a module id under {@code topolith.modules.}: the id itself and, because an environment variable
 * cannot carry a dash, the id without its dashes (D-50 known limit). Free of spring-web, so the refresh logic can use
 * it without loading that jar (INV-7). The dashless spelling is dropped when it is another known module's id, so
 * module {@code a-b} never reads module {@code ab}'s settings.
 */
final class AuthSpellings {
    private AuthSpellings() {
    }

    static List<String> of(String id, Set<String> known) {
        Set<String> names = new LinkedHashSet<>();
        names.add(id);
        String dashless = id.replace("-", "");
        if (!known.contains(dashless)) {
            names.add(dashless);
        }
        return List.copyOf(names);
    }
}
