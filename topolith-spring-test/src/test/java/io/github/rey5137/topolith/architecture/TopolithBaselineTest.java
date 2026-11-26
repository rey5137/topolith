package io.github.rey5137.topolith.architecture;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import fixture.architecture.billing.BillingImpl;
import fixture.architecture.shop.ShopArray;
import fixture.architecture.shop.ShopHack;
import fixture.architecture.shop.ShopService;
import fixture.architecture.shop.ShopSneak;
import io.github.rey5137.topolith.core.BoundaryBaseline;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.spring.test.TopolithArchitecture;
import io.github.rey5137.topolith.spring.test.TopolithBaseline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopolithBaselineTest {
    private static final String F = "fixture.architecture.";
    private static final String STORE = "freeze.store.default.";

    @TempDir
    Path store;

    @BeforeEach
    void configureStore() {
        ArchConfiguration.get().reset();
        ArchConfiguration.get().setProperty(STORE + "path", store.resolve("archunit_store").toString());
    }

    @AfterEach
    void resetConfiguration() {
        ArchConfiguration.get().reset();
    }

    private static List<ModuleDescriptor> declared() {
        return List.of(ModuleDescriptor.builder("shop").packageName(F + "shop").apiPackages(List.of(F + "shop.api"))
                        .requires(List.of("billing")).apiVersion("1.0.0").build(),
                ModuleDescriptor.builder("billing").packageName(F + "billing")
                        .apiPackages(List.of(F + "billing.api")).apiVersion("1.0.0").build());
    }

    private static List<ModuleDescriptor> cycle(String... ids) {
        return IntStream.range(0, ids.length).mapToObj(i -> ModuleDescriptor.builder(ids[i])
                .packageName(F + ids[i]).apiPackages(List.of(F + ids[i] + ".api"))
                .requires(List.of(ids[(i + 1) % ids.length])).apiVersion("1.0.0").build()).toList();
    }

    private static JavaClasses classes(Class<?>... types) {
        return new ClassFileImporter().importClasses(types);
    }

    private void allowCreation(boolean allowed) {
        ArchConfiguration.get().setProperty(STORE + "allowStoreCreation", Boolean.toString(allowed));
    }

    /** The initial, explicit freeze of the given classes. */
    private void freeze(List<ModuleDescriptor> modules, Class<?>... types) {
        allowCreation(true);
        TopolithArchitecture.verifyFrozen(modules, List.of(), classes(types));
        allowCreation(false);
    }

    private void verifyFrozen(List<ModuleDescriptor> modules, Class<?>... types) {
        TopolithArchitecture.verifyFrozen(modules, List.of(), classes(types));
    }

    private String storeText() throws IOException {
        try (Stream<Path> files = Files.walk(store)) {
            StringBuilder text = new StringBuilder();
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                text.append(Files.readString(file));
            }
            return text.toString();
        }
    }

    @Test
    void ac_arch_07_an_existing_violation_passes_and_a_new_one_fails() {
        freeze(declared(), ShopHack.class, BillingImpl.class);
        assertDoesNotThrow(() -> verifyFrozen(declared(), ShopHack.class, BillingImpl.class));
        AssertionError error = assertThrows(AssertionError.class,
                () -> verifyFrozen(declared(), ShopHack.class, ShopSneak.class, BillingImpl.class));
        assertTrue(error.getMessage().contains(ShopSneak.class.getName()), error.getMessage());
        assertFalse(error.getMessage().contains(ShopHack.class.getName()), error.getMessage());
    }

    @Test
    void ac_arch_07_a_stored_tl0017_violation_does_not_freeze_the_consistency_rule() {
        List<ModuleDescriptor> nested = List.of(declared().get(0), ModuleDescriptor.builder("inner")
                .packageName(F + "shop.inner").apiPackages(List.of(F + "shop.inner.api")).apiVersion("1.0.0").build());
        freeze(declared(), BillingImpl.class);
        var diagnostics = io.github.rey5137.topolith.core.Descriptors.nesting(nested, List.of());
        assertFalse(diagnostics.isEmpty());
        var rule = TopolithArchitecture.rule(TopolithArchitecture.Rule.DESCRIPTOR_CONSISTENCY, nested, List.of());
        var violations = new com.tngtech.archunit.library.freeze.TextFileBasedViolationStore();
        violations.initialize(ArchConfiguration.get().getSubProperties("freeze.store"));
        violations.save(rule, diagnostics.stream().map(d -> d.code() + ": " + d.description()).toList());
        AssertionError error = assertThrows(AssertionError.class, () -> verifyFrozen(nested, BillingImpl.class));
        assertTrue(error.getMessage().contains("TL0017"), error.getMessage());
    }

    @Test
    void ac_arch_07_a_violation_that_moved_lines_stays_frozen() throws IOException {
        freeze(declared(), ShopHack.class, BillingImpl.class);
        try (Stream<Path> files = Files.walk(store)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(file);
                Files.writeString(file, text.replaceAll("\\.java:\\d+", ".java:999"));
            }
        }
        assertTrue(storeText().contains(".java:999"));
        assertDoesNotThrow(() -> verifyFrozen(declared(), ShopHack.class, BillingImpl.class));
    }

    @Test
    void ac_arch_07_without_a_store_clean_classes_pass_and_nothing_is_created() {
        assertDoesNotThrow(() -> verifyFrozen(declared(), BillingImpl.class));
        assertFalse(Files.exists(store.resolve("archunit_store")));
    }

    @Test
    void ac_arch_07_without_a_store_a_violation_fails_as_unfrozen() {
        AssertionError error = assertThrows(AssertionError.class, () -> verifyFrozen(declared(), ShopHack.class));
        assertTrue(error.getMessage().contains(ShopHack.class.getName()), error.getMessage());
        assertFalse(Files.exists(store.resolve("archunit_store")), "the store must not be created by default");
    }

    @Test
    void ac_arch_08_a_store_without_the_topolith_rules_fails_and_records_nothing() throws IOException {
        allowCreation(true);
        FreezingArchRule.freeze(ArchRuleDefinition.classes().should().haveSimpleName("Nothing").as("unrelated rule"))
                .check(classes(BillingImpl.class));
        allowCreation(false);
        String before = storeText();
        AssertionError error = assertThrows(AssertionError.class,
                () -> verifyFrozen(declared(), ShopHack.class, BillingImpl.class));
        assertTrue(error.getMessage().contains(TopolithArchitecture.Rule.NON_API_ACCESS.description()) && error.getMessage().contains("allowStoreCreation"),
                error.getMessage());
        assertEquals(before, storeText());
        allowCreation(true);
        assertDoesNotThrow(() -> verifyFrozen(declared(), ShopHack.class, BillingImpl.class));
        allowCreation(false);
        assertDoesNotThrow(() -> verifyFrozen(declared(), ShopHack.class, BillingImpl.class));
    }

    @Test
    void ac_arch_07_a_frozen_cycle_passes_and_a_new_cycle_fails() {
        freeze(cycle("shop", "billing"), ShopService.class);
        assertDoesNotThrow(() -> verifyFrozen(cycle("shop", "billing"), ShopService.class));
        AssertionError error = assertThrows(AssertionError.class,
                () -> verifyFrozen(cycle("shop", "billing", "audit"), ShopService.class));
        assertTrue(error.getMessage().contains("TL0008"), error.getMessage());
    }

    @Test
    void ac_arch_08_removing_a_violation_shrinks_the_store() throws IOException {
        freeze(declared(), ShopHack.class, ShopSneak.class, BillingImpl.class);
        assertTrue(storeText().contains(ShopSneak.class.getName()));
        assertDoesNotThrow(() -> verifyFrozen(declared(), ShopHack.class, BillingImpl.class));
        assertFalse(storeText().contains(ShopSneak.class.getName()), "the fixed violation is dropped");
        assertTrue(storeText().contains(ShopHack.class.getName()));
        assertThrows(AssertionError.class,
                () -> verifyFrozen(declared(), ShopHack.class, ShopSneak.class, BillingImpl.class));
    }

    @Test
    void ac_arch_08_a_new_violation_is_not_added_to_the_store() throws IOException {
        freeze(declared(), ShopHack.class, BillingImpl.class);
        String before = storeText();
        assertThrows(AssertionError.class,
                () -> verifyFrozen(declared(), ShopHack.class, ShopSneak.class, BillingImpl.class));
        assertEquals(before, storeText());
    }

    private static void descriptors(Path out, String... ids) throws IOException {
        Path directory = Files.createDirectories(out.resolve("META-INF/topolith/modules"));
        for (String id : ids) {
            Files.writeString(directory.resolve(id + ".properties"), "id=" + id + "\n");
        }
    }

    @Test
    void ac_arch_14_generated_resource_has_exactly_the_frozen_violations(@TempDir Path out) throws IOException {
        freeze(declared(), ShopSneak.class, ShopHack.class, BillingImpl.class);
        descriptors(out, "shop", "billing");
        List<Path> written = TopolithBaseline.generate(declared(), List.of(), out);
        Path file = out.resolve("META-INF/topolith/baseline/shop.txt");
        assertEquals(List.of(file), written);
        List<String> lines = Files.readAllLines(file).stream().filter(line -> !line.startsWith("#")).toList();
        assertEquals(List.of(ShopHack.class.getName() + " -> " + BillingImpl.class.getName(),
                ShopSneak.class.getName() + " -> " + BillingImpl.class.getName()), lines);
        assertEquals(2, BoundaryBaseline.parse(Files.readString(file)).size());
        assertEquals(lines.stream().collect(Collectors.toSet()),
                BoundaryBaseline.parse(Files.readString(file)).stream().map(Object::toString)
                        .collect(Collectors.toSet()));
    }

    @Test
    void ac_arch_14_a_module_without_frozen_violations_gets_no_file_and_a_stale_one_is_removed(@TempDir Path out)
            throws IOException {
        freeze(declared(), ShopHack.class, BillingImpl.class);
        descriptors(out, "shop", "billing");
        Path stale = out.resolve("META-INF/topolith/baseline/billing.txt");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "old -> stale\n");
        TopolithBaseline.generate(declared(), List.of(), out);
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(out.resolve("META-INF/topolith/baseline/shop.txt")));
    }

    @Test
    void ac_arch_14_an_array_dependency_is_recorded_as_its_component_class_only(@TempDir Path out)
            throws IOException {
        freeze(declared(), ShopArray.class, BillingImpl.class);
        descriptors(out, "shop", "billing");
        TopolithBaseline.generate(declared(), List.of(), out);
        String text = Files.readString(out.resolve("META-INF/topolith/baseline/shop.txt"));
        assertEquals(List.of(ShopArray.class.getName() + " -> " + BillingImpl.class.getName()),
                text.lines().filter(line -> !line.startsWith("#")).toList());
    }

    @Test
    void ac_arch_14_a_custom_store_class_is_rejected(@TempDir Path out) throws IOException {
        descriptors(out, "shop", "billing");
        ArchConfiguration.get().setProperty("freeze.store", "com.example.CustomStore");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TopolithBaseline.generate(declared(), List.of(), out));
        assertTrue(error.getMessage().contains("freeze.store"), error.getMessage());
    }

    @Test
    void ac_arch_14_no_descriptors_on_the_class_path_is_an_error(@TempDir Path out) {
        AssertionError error = assertThrows(AssertionError.class, () -> TopolithBaseline.generate(out));
        assertTrue(error.getMessage().contains("No module descriptor"), error.getMessage());
    }

    @Test
    void ac_arch_14_only_modules_whose_descriptor_is_in_the_output_directory_are_written(@TempDir Path out)
            throws IOException {
        freeze(declared(), ShopHack.class, BillingImpl.class);
        descriptors(out, "billing");
        List<Path> written = TopolithBaseline.generate(declared(), List.of(), out);
        assertEquals(List.of(), written);
        assertFalse(Files.exists(out.resolve("META-INF/topolith/baseline/shop.txt")));
    }

    @Test
    void ac_arch_14_an_output_directory_without_any_descriptor_is_rejected(@TempDir Path out) throws IOException {
        freeze(declared(), ShopHack.class, BillingImpl.class);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TopolithBaseline.generate(declared(), List.of(), out));
        assertTrue(error.getMessage().contains("no module descriptor"), error.getMessage());
        assertFalse(Files.exists(out.resolve("META-INF")));
    }

    @Test
    void ac_arch_14_named_modules_are_written_into_a_directory_without_descriptors(@TempDir Path out)
            throws IOException {
        freeze(declared(), ShopHack.class, BillingImpl.class);
        List<Path> written = TopolithBaseline.generate(declared(), List.of(), out, "shop");
        Path file = out.resolve("META-INF/topolith/baseline/shop.txt");
        assertEquals(List.of(file), written);
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void ac_arch_14_an_unknown_module_id_is_rejected(@TempDir Path out) throws IOException {
        freeze(declared(), ShopHack.class, BillingImpl.class);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TopolithBaseline.generate(declared(), List.of(), out, "nope"));
        assertTrue(error.getMessage().contains("nope"), error.getMessage());
        assertFalse(Files.exists(out.resolve("META-INF")));
    }

    @Test
    void ac_arch_14_a_stale_file_of_an_unnamed_module_survives(@TempDir Path out) throws IOException {
        freeze(declared(), ShopHack.class, BillingImpl.class);
        Path stale = out.resolve("META-INF/topolith/baseline/billing.txt");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "old -> stale\n");
        TopolithBaseline.generate(declared(), List.of(), out, "shop");
        assertTrue(Files.exists(stale));
    }
}
