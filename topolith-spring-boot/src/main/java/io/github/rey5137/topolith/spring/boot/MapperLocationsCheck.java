package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.Topology;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * TL0043 (D-114, D-135): a host's {@code mybatis.mapper-locations} (and, D-135, its type alias and type handler
 * packages) that reaches a resource under a module's package path (the
 * resource ownership of D-61), in every mode. A module's mapper XML lives beside its mapper interface, where MyBatis
 * loads it, so a location that also matches it would load a {@code none} module's mappers (INV-2). It reads a property
 * and loads no MyBatis type, so it runs whether or not MyBatis is on the class path. The standalone module is the
 * application, so its own XML is the host's (the exemption of TL0018).
 */
final class MapperLocationsCheck {
    static final String PROPERTY = "mybatis.mapper-locations";
    /** The package properties (D-135): MyBatis loads every class under them, and registers type handlers globally. */
    static final List<String> PACKAGE_PROPERTIES = List.of("mybatis.type-aliases-package",
            "mybatis.type-handlers-package");

    private MapperLocationsCheck() {
    }

    static List<Diagnostic> check(Environment environment, ClassLoader loader, Topology topology,
            String standalone) {
        List<Diagnostic> found = new ArrayList<>();
        if (environment == null || topology.modulePackages().isEmpty()) {
            return found;
        }
        found.addAll(packages(environment, topology, standalone));
        String[] locations = Binder.get(environment).bind(PROPERTY, Bindable.of(String[].class)).orElse(new String[0]);
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
        Map<String, List<String>> roots = null;
        for (String raw : locations) {
            String location = raw.trim();
            if (location.isEmpty()) {
                continue;
            }
            Resource[] resources;
            try {
                resources = resolver.getResources(location);
            } catch (IOException e) {
                continue;
            }
            for (Resource resource : resources) {
                if (!resource.exists()) {
                    continue;
                }
                if (roots == null) {
                    roots = moduleRoots(loader, topology, standalone);
                }
                String url = url(resource);
                for (Map.Entry<String, List<String>> module : roots.entrySet()) {
                    if (owns(module.getValue(), module.getKey(), url, topology)) {
                        found.add(new Diagnostic(TopolithCode.TL0043, "the host's " + PROPERTY + " location '"
                                + location + "' reaches the resource " + url + ", which belongs to module '"
                                + module.getKey() + "' (under " + topology.modulePackages().get(module.getKey())
                                + ").", "Narrow " + PROPERTY + " to the host's own mapper XML; a module's mapper "
                                        + "XML lives beside its mapper interface, where MyBatis loads it."));
                        break;
                    }
                }
            }
        }
        return found;
    }

    /**
     * D-135: a host's {@code mybatis.type-aliases-package} or {@code mybatis.type-handlers-package} entry (comma or
     * semicolon separated) that is a module's package, a parent of it or inside it reaches the classes of a module that
     * is not internal. An internal module's classes are the host's to load; the standalone module is exempt.
     */
    private static List<Diagnostic> packages(Environment environment, Topology topology, String standalone) {
        List<Diagnostic> found = new ArrayList<>();
        for (String property : PACKAGE_PROPERTIES) {
            String value = environment.getProperty(property);
            if (value == null) {
                continue;
            }
            for (String raw : value.split("[,;\\s]+")) {
                String entry = raw.trim();
                while (entry.endsWith(".")) {
                    entry = entry.substring(0, entry.length() - 1);
                }
                if (entry.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, String> module : new TreeMap<>(topology.modulePackages()).entrySet()) {
                    String pack = module.getValue();
                    if (module.getKey().equals(standalone) || topology.internal().contains(module.getKey())
                            || !(pack.equals(entry) || pack.startsWith(entry + ".") || entry.startsWith(pack + "."))) {
                        continue;
                    }
                    found.add(new Diagnostic(TopolithCode.TL0043, "the host's " + property + " entry '" + entry
                            + "' reaches the classes of module '" + module.getKey() + "' (under " + pack + "), which "
                            + "is not internal.", "Narrow " + property + " to the host's own packages; MyBatis "
                            + "loads every class under it."));
                }
            }
        }
        return found;
    }

    private static String url(Resource resource) {
        try {
            return resource.getURL().toExternalForm();
        } catch (IOException e) {
            return String.valueOf(resource.getDescription());
        }
    }

    /** The class-path URLs of each module's package directory, by module id; the standalone module is exempt. */
    private static Map<String, List<String>> moduleRoots(ClassLoader loader, Topology topology,
            String standalone) {
        Map<String, List<String>> roots = new TreeMap<>();
        for (Map.Entry<String, String> module : new TreeMap<>(topology.modulePackages()).entrySet()) {
            if (module.getKey().equals(standalone)) {
                continue;
            }
            List<String> urls = new ArrayList<>();
            try {
                for (URL url : Collections.list(loader.getResources(module.getValue().replace('.', '/')))) {
                    urls.add(url.toExternalForm());
                }
            } catch (IOException e) {
                // an unreadable root owns nothing we can name
            }
            roots.put(module.getKey(), urls);
        }
        return roots;
    }

    private static boolean owns(List<String> prefixes, String id, String url, Topology topology) {
        for (String prefix : new TreeSet<>(prefixes)) {
            if (url.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")) {
                return true;
            }
        }
        String path = topology.modulePackages().get(id).replace('.', '/');
        return url.contains("!/" + path + "/");
    }
}
