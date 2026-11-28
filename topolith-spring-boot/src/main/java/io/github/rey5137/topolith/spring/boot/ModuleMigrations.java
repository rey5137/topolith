package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import io.github.rey5137.topolith.core.Topology;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Where a module's migrations live, which tool runs them and what its tracking tables are called (D-32). No optional
 * types: Flyway and Liquibase are named by string.
 */
final class ModuleMigrations {
    static final String ROOT = "db/modules/";
    static final String TABLE_PREFIX = "flyway_schema_history_";
    static final String CHANGELOG_PREFIX = "databasechangelog_";
    static final String LOCK_PREFIX = "databasechangeloglock_";
    /** Liquibase's default file name, so an adopter moves {@code db/changelog} here without renaming anything. */
    static final String MASTER = "db.changelog-master.";
    /** The formats {@code liquibase-core} reads. */
    static final List<String> MASTER_EXTENSIONS = List.of("yaml", "yml", "xml", "json", "sql");
    /** PostgreSQL silently truncates longer identifiers. */
    static final int MAX_TABLE_NAME = 63;
    /** Flyway's versioned, undo and repeatable script names. */
    private static final Pattern FLYWAY_SCRIPT = Pattern.compile("([VU]\\d.*__.+|R__.+)\\.sql");

    /** The tool that migrates a module. */
    enum Tool {
        FLYWAY("topolith.flyway."), LIQUIBASE("topolith.liquibase.");

        private final String prefix;

        Tool(String prefix) {
            this.prefix = prefix;
        }

        String runnerName(String id) {
            return prefix + id;
        }
    }

    /** Whether the bean name is one of Topolith's own module runners. */
    static boolean isModuleRunner(String beanName) {
        for (Tool tool : Tool.values()) {
            if (beanName.startsWith(tool.prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What a module ships under {@code db/modules/<id>}: whether any file has a Flyway name or is a Java migration
     * class, and the Liquibase master files (names found on the class path, one per class-path root that has it).
     */
    record Migrations(String id, boolean flyway, List<String> masters) {
        /** The module's tool, or null when it has none or both (a TL0020 case). */
        Tool tool() {
            if (flyway && masters.isEmpty()) {
                return Tool.FLYWAY;
            }
            return !flyway && masters.size() == 1 ? Tool.LIQUIBASE : null;
        }

        boolean any() {
            return flyway || !masters.isEmpty();
        }

        /** The master file name, such as {@code db.changelog-master.yaml}. */
        String master() {
            String url = masters.get(0);
            return url.substring(url.lastIndexOf('/') + 1);
        }
    }

    private ModuleMigrations() {
    }

    static String path(String id) {
        return ROOT + id;
    }

    static String location(String id) {
        return "classpath:" + path(id);
    }

    /** The Flyway history table: hyphens become underscores, which keeps the mapping injective (D-27). */
    static String table(String id) {
        return TABLE_PREFIX + id.replace('-', '_');
    }

    static String changelogTable(String id) {
        return CHANGELOG_PREFIX + id.replace('-', '_');
    }

    static String lockTable(String id) {
        return LOCK_PREFIX + id.replace('-', '_');
    }

    /** The longest tracking table name of the module's tool. */
    static int longestTable(Tool tool, String id) {
        return tool == Tool.FLYWAY ? table(id).length() : lockTable(id).length();
    }

    /** What the module ships under {@code db/modules/<id>}. */
    static Migrations migrations(ClassLoader loader, String id) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
        boolean flyway = false;
        List<String> masters = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:" + path(id) + "/**")) {
                String name = resource.getFilename();
                if (resource.isReadable() && name != null && (name.endsWith(".class")
                        || FLYWAY_SCRIPT.matcher(name).matches())) {
                    flyway = true;
                    break;
                }
            }
            for (String extension : MASTER_EXTENSIONS) {
                for (Resource resource : resolver.getResources("classpath*:" + path(id) + "/" + MASTER + extension)) {
                    masters.add(resource.getURL().toString());
                }
            }
        } catch (IOException e) {
            return new Migrations(id, flyway, masters);
        }
        return new Migrations(id, flyway, masters);
    }

    /** Whether the module has Flyway-named scripts: what a host Flyway location would run. */
    static boolean hasFlywayScripts(ClassLoader loader, String id) {
        return migrations(loader, id).flyway();
    }

    /** The internal modules that ship migrations, by id. */
    static Map<String, Migrations> withMigrations(Topology topology, ClassLoader loader) {
        Map<String, Migrations> found = new TreeMap<>();
        for (String id : new TreeSet<>(topology.internal())) {
            Migrations migrations = migrations(loader, id);
            if (migrations.any()) {
                found.put(id, migrations);
            }
        }
        return found;
    }

    /**
     * The modules Topolith runs migrations for: internal, with exactly one tool, no runner of their own (which owns
     * their migrations, whatever DataSource it uses), and either no DataSource of their own (the runner uses the
     * host's) or exactly one (the runner uses that one, D-112); several are TL0020, as is an R2DBC connection
     * factory of their own with no DataSource (D-134). Sorted by id.
     */
    static Map<String, Tool> runners(Topology topology, ClassLoader loader, Map<String, List<String>> ownDataSources,
            Set<String> ownRunners, Set<String> connectionFactoryOnly) {
        Map<String, Tool> runners = new TreeMap<>();
        withMigrations(topology, loader).forEach((id, migrations) -> {
            List<String> own = ownDataSources.getOrDefault(id, List.of());
            if (migrations.tool() != null && !ownRunners.contains(id) && own.size() <= 1
                    && !connectionFactoryOnly.contains(id)) {
                runners.put(id, migrations.tool());
            }
        });
        return runners;
    }

    /** The DataSource bean a module's runner uses: the module's one DataSource (D-112), or null for the host's. */
    static String dataSource(String id, Map<String, List<String>> ownDataSources) {
        List<String> own = ownDataSources.getOrDefault(id, List.of());
        return own.size() == 1 ? own.get(0) : null;
    }

    /** The modules of {@code runners} that {@code id} requires, directly or through modules that run nothing. */
    static Set<String> runnersRequiredBy(Topology topology, String id, Set<String> runners) {
        Set<String> found = new LinkedHashSet<>();
        Set<String> seen = new TreeSet<>();
        List<String> pending = new ArrayList<>(new TreeSet<>(topology.graph().requires(id)));
        while (!pending.isEmpty()) {
            String next = pending.remove(0);
            if (!seen.add(next)) {
                continue;
            }
            if (runners.contains(next)) {
                found.add(next);
            } else {
                pending.addAll(new TreeSet<>(topology.graph().requires(next)));
            }
        }
        return found;
    }

    /** What a module's runner waits for: every host runner, then the runners of the modules it requires. */
    static String[] dependsOn(Topology topology, String id, Map<String, Tool> runners, List<String> hostRunners) {
        List<String> dependsOn = new ArrayList<>(hostRunners);
        for (String required : runnersRequiredBy(topology, id, runners.keySet())) {
            dependsOn.add(runners.get(required).runnerName(required));
        }
        return dependsOn.toArray(String[]::new);
    }

    /** Whether the location root of a host's Flyway is, or lies above, {@code db/modules/<id>}. */
    static boolean reaches(String rootPath, String id) {
        String root = rootPath.endsWith("/") ? rootPath.substring(0, rootPath.length() - 1) : rootPath;
        String module = path(id);
        return root.isEmpty() || module.equals(root) || module.startsWith(root + "/");
    }
}
