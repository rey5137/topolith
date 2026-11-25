package io.github.rey5137.topolith.spring.test;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.library.freeze.TextFileBasedViolationStore;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.BoundaryBaseline;
import io.github.rey5137.topolith.core.BoundaryBaseline.Violation;
import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.core.ModuleDescriptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Generates the packaged baseline from the frozen ArchUnit store (R-ARCH-03, D-25, D-57). Only the non-API access
 * rule is packaged: it is the one the startup check consumes. Call it from a test or a {@code main} of the adopter
 * after freezing, with the module's resource directory (for example {@code target/classes}) as the output.
 */
@Incubating
public final class TopolithBaseline {
    private TopolithBaseline() {
    }

    /**
     * Writes {@code META-INF/topolith/baseline/<id>.txt} under {@code outputDir} for every module with a frozen
     * non-API access violation, found in the store of ArchUnit's configuration, and returns the files written. A
     * stale file of a module in {@code modules} that has no violation left is deleted after the new files are
     * written. Fails when {@code freeze.store} names a custom store class.
     */
    public static List<Path> generate(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis,
            Path outputDir) {
        Ownership ownership = new Ownership(modules, apis);
        Map<String, Set<Violation>> byModule = new TreeMap<>();
        for (String line : storedViolations(modules, apis)) {
            Violation violation = parse(line);
            String owner = ownership.owner(packageOf(violation.referencing()));
            if (owner != null) {
                byModule.computeIfAbsent(owner, id -> new TreeSet<>()).add(violation);
            }
        }
        try {
            List<Path> written = new ArrayList<>();
            if (!byModule.isEmpty()) {
                Files.createDirectories(outputDir.resolve(BoundaryBaseline.DIRECTORY));
            }
            for (Map.Entry<String, Set<Violation>> entry : byModule.entrySet()) {
                Path file = outputDir.resolve(BoundaryBaseline.resource(entry.getKey()));
                Files.writeString(file, BoundaryBaseline.format(entry.getValue()), StandardCharsets.UTF_8);
                written.add(file);
            }
            for (ModuleDescriptor module : modules) {
                if (!byModule.containsKey(module.id())) {
                    Files.deleteIfExists(outputDir.resolve(BoundaryBaseline.resource(module.id())));
                }
            }
            return written;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Like {@link #generate(Collection, Collection, Path)}, with the descriptors on the context class loader. */
    public static List<Path> generate(Path outputDir) {
        ClassLoader loader = TopolithArchitecture.contextLoader();
        List<ModuleDescriptor> modules = TopolithArchitecture.readModules(loader);
        TopolithArchitecture.requireModules(modules);
        return generate(modules, TopolithArchitecture.readApis(loader), outputDir);
    }

    private static List<String> storedViolations(Collection<ModuleDescriptor> modules,
            Collection<ApiDescriptor> apis) {
        if (ArchConfiguration.get().containsProperty("freeze.store")) {
            throw new IllegalStateException("Baseline generation reads ArchUnit's default text store and does not "
                    + "support a custom 'freeze.store' class; remove that property to generate the baseline.");
        }
        var rule = TopolithArchitecture.rules(modules, apis).get(0);
        Properties properties = ArchConfiguration.get().getSubProperties("freeze.store");
        TextFileBasedViolationStore store = new TextFileBasedViolationStore();
        store.initialize(properties);
        return store.contains(rule) ? store.getViolations(rule) : List.of();
    }

    /** A stored message starts {@code <referencing> -> <referenced>: }. */
    private static Violation parse(String message) {
        int colon = message.indexOf(": ");
        String head = colon < 0 ? message : message.substring(0, colon);
        Set<Violation> parsed = BoundaryBaseline.parse(head);
        if (parsed.size() != 1) {
            throw new IllegalStateException("Stored violation is not a non-API access violation: " + message);
        }
        return parsed.iterator().next();
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }
}
