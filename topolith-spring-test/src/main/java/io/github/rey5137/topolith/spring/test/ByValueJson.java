package io.github.rey5137.topolith.spring.test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compares two JSON trees (maps, lists and scalars) and names the first path where they differ. */
final class ByValueJson {
    private ByValueJson() { }

    /** The first differing path under {@code path}, or null when the trees are equal. */
    static String diff(Object expected, Object actual, String path) {
        if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right) {
            Set<Object> keys = new LinkedHashSet<>(left.keySet());
            keys.addAll(right.keySet());
            for (Object key : keys) {
                String found = diff(left.get(key), right.get(key), path + "." + key);
                if (found != null) { return found; }
            }
            return null;
        }
        if (expected instanceof List<?> left && actual instanceof List<?> right) {
            int common = Math.min(left.size(), right.size());
            for (int i = 0; i < common; i++) {
                String found = diff(left.get(i), right.get(i), path + "[" + i + "]");
                if (found != null) { return found; }
            }
            return left.size() == right.size() ? null : path + "[" + common + "]";
        }
        return Objects.equals(expected, actual) ? null : path;
    }
}
