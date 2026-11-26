package io.github.rey5137.topolith.spring.test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * What the by-value proxies of one context keep between calls (R-TEST-04): the argument copies and returned objects
 * with their serialized form, and the violations of the current test method. Present only in a context that enabled
 * the proxy; released after each test method.
 */
final class ByValueRecorder {
    /** An object a provider holds, with its form when the call ended. */
    record Kept(String where, Object object, Type type, Object tree) { }

    private final Supplier<ByValueSerializer> factory;
    private volatile ByValueSerializer serializer;
    private final Map<String, List<Kept>> kept = new TreeMap<>();
    private final List<AssertionError> violations = new ArrayList<>();

    ByValueRecorder(Supplier<ByValueSerializer> factory) { this.factory = factory; }

    /** Built on first use; a context with no JSON converter fails here. */
    ByValueSerializer serializer() {
        ByValueSerializer known = serializer;
        if (known == null) {
            synchronized (this) {
                known = serializer;
                if (known == null) {
                    known = factory.get();
                    serializer = known;
                }
            }
        }
        return known;
    }

    synchronized AssertionError record(AssertionError violation) {
        violations.add(violation);
        return violation;
    }

    synchronized void keep(String module, Kept item) {
        kept.computeIfAbsent(module, key -> new ArrayList<>()).add(item);
    }

    /** Serializes what the module's provider kept again; the first change is recorded, dropped and returned. */
    synchronized AssertionError recheck(String module) {
        AssertionError first = null;
        List<Kept> items = kept.get(module);
        if (items == null) { return null; }
        for (Iterator<Kept> each = items.iterator(); each.hasNext();) {
            Kept item = each.next();
            String path;
            try {
                path = ByValueJson.diff(item.tree(), serializer().tree(item.object(), item.type()), "$");
            } catch (RuntimeException e) {
                path = "$ (no longer serializes: " + e.getMessage() + ")";
            }
            if (path != null) {
                each.remove();
                AssertionError violation = record(new AssertionError("By-value proxy: module '" + module + "', "
                        + item.where() + " (" + item.type().getTypeName() + ") was changed after the call returned,"
                        + " at " + path));
                if (first == null) { first = violation; } else { first.addSuppressed(violation); }
            }
        }
        return first;
    }

    /** Checks everything kept again, then returns the violations of the method and releases all state. */
    List<AssertionError> finish() {
        Set<String> modules;
        synchronized (this) { modules = new LinkedHashSet<>(kept.keySet()); }
        for (String module : modules) { recheck(module); }
        return release();
    }

    /** Drops the kept objects and returns the violations recorded since the last release. */
    synchronized List<AssertionError> release() {
        List<AssertionError> found = List.copyOf(violations);
        violations.clear();
        kept.clear();
        return found;
    }

    /** Drops the kept objects only; the violations stay for the next test method to report. */
    synchronized void releaseKept() { kept.clear(); }

    /** The violations recorded outside any test method, such as while the context started; they are cleared. */
    synchronized List<AssertionError> drainViolations() {
        List<AssertionError> found = List.copyOf(violations);
        violations.clear();
        return found;
    }
}
