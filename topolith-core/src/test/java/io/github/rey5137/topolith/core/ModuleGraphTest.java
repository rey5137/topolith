package io.github.rey5137.topolith.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleGraphTest {
    private static ModuleDescriptor module(String id, List<String> requires, List<String> optional, String source) {
        return ModuleDescriptor.builder(id).apiPackages(List.of("com.acme." + id)).apiVersion("1.0.0")
                .requires(requires).optional(optional).source(source).build();
    }

    private static ModuleDescriptor module(String id, String... requires) {
        return module(id, List.of(requires), List.of(), "jar:file:/" + id + ".jar!/" + id + ".properties");
    }

    @Test
    void ac_desc_08_duplicate_ids_fail_with_tl0006_naming_both_sources() {
        ModuleDescriptor one = module("orders", List.of(), List.of(), "jar:file:/one.jar!/orders.properties");
        ModuleDescriptor two = module("orders", List.of(), List.of(), "jar:file:/two.jar!/orders.properties");
        TopolithException failure = assertThrows(TopolithException.class,
                () -> ModuleGraph.of(List.of(two, one, module("billing"))));
        assertEquals(TopolithCode.TL0006, failure.code());
        assertEquals(1, failure.diagnostics().size());
        String description = failure.diagnostics().get(0).description();
        assertTrue(description.contains("'orders'"), description);
        assertTrue(description.contains("one.jar") && description.contains("two.jar"), description);
        assertTrue(description.indexOf("one.jar") < description.indexOf("two.jar"), description);
    }

    @Test
    void ac_desc_08_required_cycle_fails_with_tl0008_and_a_path_from_the_smallest_id() {
        TopolithException failure = assertThrows(TopolithException.class,
                () -> ModuleGraph.of(List.of(module("c", "a"), module("b", "c"), module("a", "b"), module("x"))));
        assertEquals(1, failure.diagnostics().size());
        assertEquals(TopolithCode.TL0008, failure.code());
        assertTrue(failure.diagnostics().get(0).description().contains("a -> b -> c -> a"),
                failure.diagnostics().get(0).description());
    }

    @Test
    void ac_desc_08_each_cycle_group_is_one_diagnostic_in_id_order() {
        TopolithException failure = assertThrows(TopolithException.class, () -> ModuleGraph.of(
                List.of(module("y", "z"), module("z", "y"), module("a", "b"), module("b", "a"))));
        assertEquals(2, failure.diagnostics().size());
        assertTrue(failure.diagnostics().get(0).description().contains("a -> b -> a"));
        assertTrue(failure.diagnostics().get(1).description().contains("y -> z -> y"));
    }

    @Test
    void ac_desc_08_an_optional_only_cycle_passes() {
        ModuleGraph graph = ModuleGraph.of(List.of(
                module("a", List.of(), List.of("b"), "a.properties"),
                module("b", List.of(), List.of("a"), "b.properties")));
        assertEquals(Set.of("b"), graph.optional("a"));
    }

    @Test
    void duplicates_are_reported_before_cycles() {
        TopolithException failure = assertThrows(TopolithException.class, () -> ModuleGraph.of(
                List.of(module("a", "b"), module("b", "a"), module("b", "a"))));
        assertEquals(TopolithCode.TL0006, failure.code());
    }

    @Test
    void ac_desc_09_an_absent_optional_dependency_is_not_unmet_but_an_absent_required_one_is() {
        ModuleGraph graph = ModuleGraph.of(List.of(
                module("orders", List.of("billing", "ghost"), List.of("search"), "orders.properties"),
                module("billing")));
        assertEquals(Map.of("orders", Set.of("ghost")), graph.unmetRequirements(Set.of("orders", "billing")));
        assertEquals(Map.of("orders", Set.of("billing", "ghost")), graph.unmetRequirements(Set.of("orders")));
        assertTrue(graph.unmetRequirements(Set.of("billing")).isEmpty());
    }

    @Test
    void accessors_expose_modules_and_edges_in_id_order() {
        ModuleGraph graph = ModuleGraph.of(List.of(module("b"), module("a", "b")));
        assertEquals(List.of("a", "b"), graph.modules().stream().map(ModuleDescriptor::id).toList());
        assertEquals(Set.of("b"), graph.requires("a"));
        assertTrue(graph.module("zzz").isEmpty());
        assertTrue(graph.requires("zzz").isEmpty());
        assertTrue(graph.optional("zzz").isEmpty());
    }

    @Test
    void duplicate_api_ids_fail_with_tl0006_but_a_module_and_an_api_may_share_an_id() {
        ApiDescriptor one = ApiDescriptor.builder("orders").apiPackages(List.of("com.acme.orders"))
                .apiVersion("1.0.0").source("one/orders.properties").build();
        ApiDescriptor two = ApiDescriptor.builder("orders").apiPackages(List.of("com.acme.orders"))
                .apiVersion("1.0.0").source("two/orders.properties").build();
        TopolithException failure =
                assertThrows(TopolithException.class, () -> Descriptors.indexApis(List.of(one, two)));
        assertEquals(TopolithCode.TL0006, failure.code());
        assertTrue(failure.getMessage().contains("one/orders.properties"));
        assertTrue(failure.getMessage().contains("two/orders.properties"));
        assertEquals(Set.of("orders"), Descriptors.indexApis(List.of(one)).keySet());
        ModuleGraph.of(List.of(module("orders")));
    }
}
