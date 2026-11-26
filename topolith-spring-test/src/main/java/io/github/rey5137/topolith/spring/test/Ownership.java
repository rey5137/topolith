package io.github.rey5137.topolith.spring.test;

import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.ModuleDescriptor;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which module owns a package. An api package belongs to the module whose descriptor lists it, even outside that
 * module's {@code package} (a separate API artifact); any other package belongs to the module whose
 * {@code package} contains it, and to no module when none or several do (nested packages, D-56).
 */
final class Ownership {
    private final Map<String, ModuleDescriptor> modules = new TreeMap<>();
    private final Map<String, String> apiOwners = new HashMap<>();
    private final Map<String, Set<String>> claims = new TreeMap<>();
    private final Map<String, Set<String>> apiPackages = new TreeMap<>();

    Ownership(Collection<ModuleDescriptor> moduleDescriptors, Collection<ApiDescriptor> apiDescriptors) {
        for (ModuleDescriptor module : moduleDescriptors) {
            modules.put(module.id(), module);
            apiPackages.computeIfAbsent(module.id(), id -> new TreeSet<>()).addAll(module.apiPackages());
        }
        for (ApiDescriptor api : apiDescriptors) {
            apiPackages.computeIfAbsent(api.id(), id -> new TreeSet<>()).addAll(api.apiPackages());
        }
        apiPackages.forEach((id, packages) -> packages.forEach(name -> {
            apiOwners.putIfAbsent(name, id);
            claims.computeIfAbsent(name, key -> new TreeSet<>()).add(id);
        }));
    }

    /** The api packages that more than one descriptor id lists, with the ids; no owner can be picked for them. */
    Map<String, Set<String>> contestedApiPackages() {
        Map<String, Set<String>> contested = new TreeMap<>();
        claims.forEach((name, ids) -> {
            if (ids.size() > 1) {
                contested.put(name, ids);
            }
        });
        return contested;
    }

    Collection<ModuleDescriptor> modules() {
        return modules.values();
    }

    /**
     * The owning module id of a package, or null: none claims it, or several module packages contain it (nested
     * packages are a TL0017 consistency failure, never resolved by longest match, D-56).
     */
    String owner(String packageName) {
        String api = apiOwners.get(packageName);
        if (api != null) {
            return api;
        }
        String found = null;
        for (ModuleDescriptor module : modules.values()) {
            String root = module.packageName();
            if (packageName.equals(root) || packageName.startsWith(root + ".")) {
                if (found != null) {
                    return null;
                }
                found = module.id();
            }
        }
        return found;
    }

    boolean isApi(String packageName) {
        return apiOwners.containsKey(packageName);
    }

    Set<String> allPackages() {
        Set<String> all = new TreeSet<>(apiOwners.keySet());
        modules.values().forEach(module -> all.add(module.packageName()));
        return all;
    }
}
