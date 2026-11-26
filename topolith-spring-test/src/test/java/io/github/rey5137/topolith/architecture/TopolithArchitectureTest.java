package io.github.rey5137.topolith.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import fixture.architecture.billing.BillingController;
import fixture.architecture.billing.BillingImpl;
import fixture.architecture.contracts.PageReturn;
import fixture.architecture.billingapi.RemoteBillingApi;
import fixture.architecture.shop.ShopHack;
import fixture.architecture.shop.ShopHandlesOutage;
import fixture.architecture.shop.ShopRemote;
import fixture.architecture.shop.ShopService;
import fixture.architecture.shop.ShopTopologyAccess;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.spring.test.TopolithArchitecture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopolithArchitectureTest {
    private static final String F = "fixture.architecture.";

    private static ModuleDescriptor shop(List<String> requires, List<String> optional) {
        return ModuleDescriptor.builder("shop").packageName(F + "shop").apiPackages(List.of(F + "shop.api"))
                .requires(requires).optional(optional).apiVersion("1.0.0").build();
    }

    private static ModuleDescriptor billing(List<String> api, List<String> requires) {
        return ModuleDescriptor.builder("billing").packageName(F + "billing").apiPackages(api).requires(requires)
                .apiVersion("1.0.0").build();
    }

    private static JavaClasses classes(Class<?>... types) {
        return new ClassFileImporter().importClasses(types);
    }

    private static AssertionError fails(List<ModuleDescriptor> modules, List<ApiDescriptor> apis,
            JavaClasses classes) {
        return assertThrows(AssertionError.class, () -> TopolithArchitecture.verify(modules, apis, classes));
    }

    private static void passes(List<ModuleDescriptor> modules, List<ApiDescriptor> apis, JavaClasses classes) {
        assertDoesNotThrow(() -> TopolithArchitecture.verify(modules, apis, classes));
    }

    private static List<ModuleDescriptor> declared() {
        return List.of(shop(List.of("billing"), List.of()), billing(List.of(F + "billing.api"), List.of()));
    }

    @Test
    void ac_arch_19_module_dependency_on_topolith_core_fails() {
        AssertionError error = fails(declared(), List.of(), classes(ShopTopologyAccess.class));
        assertTrue(error.getMessage().contains("Topology"), error.getMessage());
    }

    @Test
    void ac_arch_20_module_dependency_on_contract_passes() {
        passes(declared(), List.of(), classes(ShopHandlesOutage.class));
    }

    @Test
    void ac_arch_01_non_api_access_fails_naming_modules_and_class() {
        AssertionError error = fails(declared(), List.of(), classes(ShopHack.class, BillingImpl.class));
        assertTrue(error.getMessage().contains("module 'shop'"), error.getMessage());
        assertTrue(error.getMessage().contains("module 'billing'"), error.getMessage());
        assertTrue(error.getMessage().contains(BillingImpl.class.getName()), error.getMessage());
    }

    @Test
    void ac_arch_01_access_to_a_declared_api_package_passes() {
        passes(declared(), List.of(), classes(ShopService.class));
    }

    @Test
    void ac_arch_01_api_package_outside_the_module_package_passes_and_non_api_fails() {
        List<ModuleDescriptor> modules = List.of(shop(List.of("billing"), List.of()),
                billing(List.of(F + "billingapi"), List.of()));
        passes(modules, List.of(), classes(ShopRemote.class, RemoteBillingApi.class));
        // the module's own api list is now billingapi, so billing.api is a plain internal package
        String message = fails(modules, List.of(), classes(ShopService.class)).getMessage();
        assertTrue(message.contains("not in an API package") && message.contains("BillingApi"), message);
    }

    @Test
    void ac_arch_01_api_descriptor_lists_the_api_package() {
        ModuleDescriptor billing = billing(List.of(F + "billing.api"), List.of());
        ApiDescriptor api = ApiDescriptor.builder("billing").apiPackages(List.of(F + "billingapi"))
                .apiVersion("1.0.0").build();
        passes(List.of(shop(List.of("billing"), List.of()), billing), List.of(api),
                classes(ShopRemote.class, RemoteBillingApi.class));
    }

    @Test
    void ac_build_07_a_module_using_another_modules_implementation_fails() {
        AssertionError error = fails(declared(), List.of(), classes(ShopHack.class));
        assertTrue(error.getMessage().contains(BillingImpl.class.getName()), error.getMessage());
    }

    @Test
    void ac_arch_01_a_module_controller_implementing_its_own_contract_is_intra_module() {
        passes(declared(), List.of(), classes(BillingController.class, BillingImpl.class));
    }

    @Test
    void ac_arch_01_contract_and_exception_types_belong_to_no_module() {
        passes(declared(), List.of(), classes(ShopHandlesOutage.class));
    }

    @Test
    void ac_arch_02_access_without_a_declared_dependency_fails() {
        List<ModuleDescriptor> modules = List.of(shop(List.of(), List.of()),
                billing(List.of(F + "billing.api"), List.of()));
        AssertionError error = fails(modules, List.of(), classes(ShopService.class));
        assertTrue(error.getMessage().contains("module 'shop'"), error.getMessage());
        assertTrue(error.getMessage().contains("neither requires nor optional"), error.getMessage());
    }

    @Test
    void ac_arch_04_access_to_an_optional_dependency_api_passes() {
        passes(List.of(shop(List.of(), List.of("billing")), billing(List.of(F + "billing.api"), List.of())),
                List.of(), classes(ShopService.class));
    }

    @Test
    void ac_arch_03_a_requires_cycle_fails() {
        List<ModuleDescriptor> modules = List.of(shop(List.of("billing"), List.of()),
                billing(List.of(F + "billing.api"), List.of("shop")));
        AssertionError error = fails(modules, List.of(), classes(ShopService.class));
        assertTrue(error.getMessage().contains("TL0008"), error.getMessage());
        assertTrue(error.getMessage().contains("shop") && error.getMessage().contains("billing"));
    }

    @Test
    void ac_arch_03_an_optional_cycle_passes() {
        passes(List.of(shop(List.of(), List.of("billing")), billing(List.of(F + "billing.api"), List.of())),
                List.of(), classes(ShopService.class));
        ModuleDescriptor back = ModuleDescriptor.builder("billing").packageName(F + "billing")
                .apiPackages(List.of(F + "billing.api")).optional(List.of("shop")).apiVersion("1.0.0").build();
        passes(List.of(shop(List.of("billing"), List.of()), back), List.of(), classes(ShopService.class));
    }

    @Test
    void rules_are_exposed_one_per_check() {
        assertEquals(6, TopolithArchitecture.rules(declared(), List.of()).size());
        for (TopolithArchitecture.Rule rule : TopolithArchitecture.Rule.values()) {
            assertEquals(rule.description(), TopolithArchitecture.rule(rule, declared(), List.of())
                    .getDescription());
        }
    }

    /** The descriptions are ArchUnit freeze-store keys (D-64): a rename must fail this test. */
    @Test
    void ac_arch_07_rule_descriptions_are_the_documented_store_keys() {
        assertEquals(List.of("topolith: non-API access", "topolith: undeclared dependency",
                        "topolith: requires cycle", "topolith: descriptor consistency",
                        "topolith: Topolith dependency", "topolith: contract types"),
                java.util.Arrays.stream(TopolithArchitecture.Rule.values())
                        .map(TopolithArchitecture.Rule::description).toList());
        assertEquals(List.of(TopolithArchitecture.Rule.values().length),
                List.of(TopolithArchitecture.rules(declared(), List.of()).size()));
    }

    private static ModuleDescriptor nested() {
        return ModuleDescriptor.builder("inner").packageName(F + "shop.inner")
                .apiPackages(List.of(F + "shop.inner.api")).apiVersion("1.0.0").build();
    }

    @Test
    void ac_arch_04_nested_module_packages_fail_the_consistency_rule_with_tl0017() {
        AssertionError error = fails(List.of(shop(List.of(), List.of()), nested()), List.of(), classes(ShopService.class));
        assertTrue(error.getMessage().contains(TopolithArchitecture.Rule.DESCRIPTOR_CONSISTENCY.description())
                && error.getMessage().contains("TL0017"), error.getMessage());
        AssertionError apiUnder = fails(List.of(shop(List.of(), List.of()), ModuleDescriptor.builder("other")
                .packageName(F + "other").apiPackages(List.of(F + "shop.hidden")).apiVersion("1.0.0").build()),
                List.of(), classes(ShopService.class));
        assertTrue(apiUnder.getMessage().contains("TL0017"), apiUnder.getMessage());
    }

    private static final Map<String, String> BILLING_SOURCES = Map.of(
            "ext/billing/api/BillingApi.java", "package ext.billing.api; public interface BillingApi { }",
            "ext/billing/BillingImpl.java", "package ext.billing; public class BillingImpl { }");

    private static void writeModules(Path root) throws IOException {
        write(root, "modules/shop", ModuleDescriptor.builder("shop").packageName("ext.shop")
                .apiPackages(List.of("ext.shop.api")).requires(List.of("billing")).apiVersion("1.0.0").build());
        write(root, "modules/billing", ModuleDescriptor.builder("billing").packageName("ext.billing")
                .apiPackages(List.of("ext.billing.api")).apiVersion("1.0.0").build());
        compile(root, BILLING_SOURCES);
    }

    private static final String SHOP_OK =
            "package ext.shop; public class ShopService { ext.billing.api.BillingApi a; }";
    private static final String SHOP_BAD = "package ext.shop; public class ShopHack { ext.billing.BillingImpl i; }";

    @Test
    void ac_arch_01_verify_checks_classes_found_through_the_given_loader_only(@TempDir Path dir) throws IOException {
        writeModules(dir);
        compile(dir, Map.of("ext/shop/ShopService.java", SHOP_OK));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            assertDoesNotThrow(() -> TopolithArchitecture.verify(loader));
        }
        compile(dir, Map.of("ext/shop/ShopHack.java", SHOP_BAD));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            AssertionError error = assertThrows(AssertionError.class, () -> TopolithArchitecture.verify(loader));
            assertTrue(error.getMessage().contains("ext.billing.BillingImpl"), error.getMessage());
        }
    }

    @Test
    void ac_arch_01_verify_does_not_check_test_classes(@TempDir Path dir) throws IOException {
        Path main = dir.resolve("main");
        Path tests = dir.resolve("target/test-classes");
        writeModules(main);
        compile(main, Map.of("ext/shop/ShopService.java", SHOP_OK));
        compile(tests, Map.of("ext/shop/ShopHack.java", SHOP_BAD), main);
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {main.toUri().toURL(), tests.toUri().toURL()}, null)) {
            assertDoesNotThrow(() -> TopolithArchitecture.verify(loader));
        }
    }

    @Test
    void ac_arch_01_verify_fails_when_no_module_descriptor_is_found(@TempDir Path dir) throws IOException {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            AssertionError error = assertThrows(AssertionError.class, () -> TopolithArchitecture.verify(loader));
            assertTrue(error.getMessage().contains("No module descriptor"), error.getMessage());
        }
    }

    /** Every rule has a deliberately failing fixture (AC-CI-02): a rule with no entry here fails this test. */
    @Test
    void ac_ci_02_every_architecture_rule_has_a_failing_fixture() {
        Map<TopolithArchitecture.Rule, Supplier<AssertionError>> failing = new java.util.EnumMap<>(
                TopolithArchitecture.Rule.class);
        failing.put(TopolithArchitecture.Rule.NON_API_ACCESS, () -> fails(declared(), List.of(), classes(ShopHack.class)));
        failing.put(TopolithArchitecture.Rule.UNDECLARED_DEPENDENCY, () -> fails(List.of(shop(List.of(), List.of()),
                billing(List.of(F + "billing.api"), List.of())), List.of(), classes(ShopService.class)));
        failing.put(TopolithArchitecture.Rule.REQUIRES_CYCLE, () -> fails(List.of(shop(List.of("billing"), List.of()),
                billing(List.of(F + "billing.api"), List.of("shop"))), List.of(), classes(ShopService.class)));
        failing.put(TopolithArchitecture.Rule.DESCRIPTOR_CONSISTENCY, () -> fails(List.of(declared().get(0), declared().get(0)),
                List.of(), classes(ShopService.class)));
        failing.put(TopolithArchitecture.Rule.CONTRACT_TYPES, () -> fails(List.of(ModuleDescriptor.builder("catalog").packageName(F + "shop")
                .apiPackages(List.of(F + "contracts")).apiVersion("1.0.0").build()), List.of(),
                classes(PageReturn.class)));
        failing.put(TopolithArchitecture.Rule.TOPOLITH_DEPENDENCY, () -> fails(declared(), List.of(), classes(ShopTopologyAccess.class)));
        for (TopolithArchitecture.Rule rule : TopolithArchitecture.Rule.values()) {
            Supplier<AssertionError> fixture = failing.get(rule);
            assertTrue(fixture != null, "no failing fixture for rule: " + rule);
            String message = fixture.get().getMessage();
            assertTrue(message.contains(rule.description()), "fixture for '" + rule
                    + "' failed under another rule: " + message);
        }
        assertEquals(TopolithArchitecture.Rule.values().length, failing.size());
    }

    private static void compile(Path root, Map<String, String> sources, Path... classpath) throws IOException {
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = root.resolve("src/" + source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file.toString());
        }
        Files.createDirectories(root);
        List<String> args = new ArrayList<>(
                List.of("-d", root.toString(), "-proc:none", "-cp", java.util.stream.Stream
                        .concat(java.util.stream.Stream.of(root), java.util.Arrays.stream(classpath))
                        .map(Path::toString).collect(java.util.stream.Collectors.joining(
                                java.io.File.pathSeparator))));
        args.addAll(files);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
        assertEquals(0, result, "the fixture sources compile");
    }

    private static void write(Path root, String name, ModuleDescriptor module) throws IOException {
        Path file = root.resolve("META-INF/topolith/" + name + ".properties");
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
            Descriptors.write(module, out);
        }
    }
}
