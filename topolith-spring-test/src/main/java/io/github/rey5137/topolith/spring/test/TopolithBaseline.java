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
import java.util.function.Predicate;

/**
 * Generates the packaged baseline from the frozen ArchUnit store (R-ARCH-03, D-25, D-57). Only the non-API access
 * rule is packaged: it is the one the startup check consumes. Call it from a test or a {@code main} of the adopter
 * after freezing, with the module's resource directory (for example {@code target/classes}) as the output.
 */
@Incubating
public final class TopolithBaseline {
    private static final String MODULES = "META-INF/topolith/modules/";

    private TopolithBaseline() {
    }

    /**
     * Writes {@code META-INF/topolith/baseline/<id>.txt} under {@code outputDir} for every module with a frozen
     * non-API access violation, found in the store of ArchUnit's configuration, and returns the files written. A
     * stale file of a module in {@code modules} that has no violation left is deleted after the new files are
     * written. A module's file is written only when its descriptor is under {@code outputDir}
     * ({@code META-INF/topolith/modules/<id>.properties}): a baseline lives in the class-path root of its descriptor
     * (D-63), so violations of other modules in the store are skipped and their files left alone. Fails when
     * {@code freeze.store} names a custom store class, and with {@link IllegalArgumentException} when
     * {@code outputDir} holds no module descriptor at all (a wrong output directory).
     */
    public static List<Path> generate(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis,
            Path outputDir) {
        if (!hasAnyDescriptor(outputDir)) {
            throw new IllegalArgumentException("'" + outputDir + "' holds no module descriptor (" + MODULES
                    + "<id>.properties); pass the output directory that holds the module's descriptor.");
        }
        return write(modules, apis, outputDir, id -> hasDescriptor(outputDir, id));
    }

    /**
     * Like {@link #generate(Collection, Collection, Path)}, but writes the files of the named modules into any
     * directory, such as {@code src/main/resources}, with no descriptor needed there (D-63). Only the named modules
     * are written, and only their stale files deleted. Fails with {@link IllegalArgumentException} when no id is
     * given or an id is not in {@code modules}.
     */
    public static List<Path> generate(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis,
            Path outputDir, String... moduleIds) {
        Set<String> selected = new TreeSet<>(List.of(moduleIds));
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("Name at least one module id to generate a baseline for.");
        }
        for (String id : selected) {
            if (modules.stream().noneMatch(module -> module.id().equals(id))) {
                throw new IllegalArgumentException("Unknown module id '" + id + "'; known: "
                        + modules.stream().map(ModuleDescriptor::id).sorted().toList() + ".");
            }
        }
        return write(modules, apis, outputDir, selected::contains);
    }

    private static List<Path> write(Collection<ModuleDescriptor> modules, Collection<ApiDescriptor> apis,
            Path outputDir, Predicate<String> selected) {
        Ownership ownership = new Ownership(modules, apis);
        Map<String, Set<Violation>> byModule = new TreeMap<>();
        for (String line : storedViolations(modules, apis)) {
            Violation violation = parse(line);
            String owner = ownership.owner(packageOf(violation.referencing()));
            if (owner != null && selected.test(owner)) {
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
                if (selected.test(module.id()) && !byModule.containsKey(module.id())) {
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
        return generate(contextModules(), TopolithArchitecture.readApis(TopolithArchitecture.contextLoader()),
                outputDir);
    }

    /** Like {@link #generate(Collection, Collection, Path, String...)}, with the descriptors on the context loader. */
    public static List<Path> generate(Path outputDir, String... moduleIds) {
        return generate(contextModules(), TopolithArchitecture.readApis(TopolithArchitecture.contextLoader()),
                outputDir, moduleIds);
    }

    private static List<ModuleDescriptor> contextModules() {
        List<ModuleDescriptor> modules = TopolithArchitecture.readModules(TopolithArchitecture.contextLoader());
        TopolithArchitecture.requireModules(modules);
        return modules;
    }

    private static List<String> storedViolations(Collection<ModuleDescriptor> modules,
            Collection<ApiDescriptor> apis) {
        if (ArchConfiguration.get().containsProperty("freeze.store")) {
            throw new IllegalStateException("Baseline generation reads ArchUnit's default text store and does not "
                    + "support a custom 'freeze.store' class; remove that property to generate the baseline.");
        }
        var rule = TopolithArchitecture.rule(TopolithArchitecture.Rule.NON_API_ACCESS, modules, apis);
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

    private static boolean hasAnyDescriptor(Path outputDir) {
        Path directory = outputDir.resolve(MODULES);
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var files = Files.list(directory)) {
            return files.anyMatch(file -> file.getFileName().toString().endsWith(".properties"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean hasDescriptor(Path outputDir, String id) {
        return Files.isRegularFile(outputDir.resolve(MODULES + id + ".properties"));
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }
}
