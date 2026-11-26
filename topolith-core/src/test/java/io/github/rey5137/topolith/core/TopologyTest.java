package io.github.rey5137.topolith.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyTest {
    private static ModuleDescriptor module(String id, String packageName, String... requires) {
        return ModuleDescriptor.builder(id).packageName(packageName).apiPackages(List.of(packageName + "api"))
                .apiVersion("1.0.0").requires(List.of(requires)).source("jar:/" + id + ".jar!/" + id + ".properties")
                .build();
    }

    private static ApiDescriptor api(String id, String... packages) {
        return ApiDescriptor.builder(id).apiPackages(List.of(packages)).apiVersion("1.0.0")
                .source("jar:/" + id + "-api.jar!/" + id + ".properties").build();
    }

    private static Map<String, String> modes(String... pairs) {
        Map<String, String> modes = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            modes.put(pairs[i], pairs[i + 1]);
        }
        return modes;
    }

    private static TopolithException fails(List<ModuleDescriptor> modules, List<ApiDescriptor> apis,
            Map<String, String> declared, String standalone) {
        return assertThrows(TopolithException.class, () -> Topology.resolve(modules, apis, declared, standalone));
    }

    private static List<TopolithCode> codes(TopolithException failure) {
        return failure.diagnostics().stream().map(Diagnostic::code).toList();
    }

    private final ModuleDescriptor user = module("user", "com.acme.user");
    private final ModuleDescriptor payment = module("payment", "com.acme.payment", "user");

    @Test
    void ac_val_02_internal_and_external_api_providers_conflict() {
        ModuleDescriptor internal = ModuleDescriptor.builder("alpha").packageName("fixture.alpha")
                .apiPackages(List.of("fixture")).apiVersion("1.0").provides(List.of("fixture.Contract")).build();
        ApiDescriptor external = ApiDescriptor.builder("beta").apiPackages(List.of("fixture")).apiVersion("1.0")
                .provides(List.of("fixture.Contract")).build();
        TopolithException failure = fails(List.of(internal), List.of(external),
                modes("alpha", "internal", "beta", "external"), null);
        assertEquals(List.of(TopolithCode.TL0005), codes(failure));
        assertTrue(failure.getMessage().contains("fixture.Contract"));
        assertTrue(failure.getMessage().contains("alpha") && failure.getMessage().contains("beta"));
    }

    @Test
    void ac_val_02_matching_internal_api_is_not_a_second_provider() {
        ModuleDescriptor internal = ModuleDescriptor.builder("alpha").packageName("fixture.alpha")
                .apiPackages(List.of("fixture")).apiVersion("1.0").provides(List.of("fixture.Contract")).build();
        ApiDescriptor api = ApiDescriptor.builder("alpha").apiPackages(List.of("fixture")).apiVersion("1.0")
                .provides(List.of("fixture.Contract")).build();
        assertEquals(Mode.INTERNAL, Topology.resolve(List.of(internal), List.of(api),
                modes("alpha", "internal"), null).mode("alpha"));
    }

    @Test
    void ac_val_04_external_module_and_api_may_declare_different_contracts() {
        ModuleDescriptor external = ModuleDescriptor.builder("alpha").packageName("fixture.alpha")
                .apiPackages(List.of("fixture")).apiVersion("1.0")
                .provides(List.of("fixture.ImplementationContract")).build();
        ApiDescriptor api = ApiDescriptor.builder("alpha").apiPackages(List.of("fixture")).apiVersion("1.0")
                .provides(List.of("fixture.ApiContract")).build();
        assertEquals(Mode.EXTERNAL, Topology.resolve(List.of(external), List.of(api),
                modes("alpha", "external"), null).mode("alpha"));
    }

    @Test
    void ac_mode_01_each_explicit_mode_resolves_to_its_declared_value() {
        Topology topology = Topology.resolve(List.of(user, payment), List.of(),
                modes("user", "internal", "payment", " External "), null);
        assertEquals(Mode.INTERNAL, topology.mode("user"));
        assertEquals(Mode.EXTERNAL, topology.mode("payment"));
        assertEquals(Set.of("user"), topology.internal());
        assertEquals(Set.of("payment"), topology.idsOf(Mode.EXTERNAL));
        assertEquals(Set.of("user", "payment"), topology.known());
        Topology none = Topology.resolve(List.of(user, payment), List.of(), modes("user", "NONE", "payment", "none"),
                null);
        assertEquals(Mode.NONE, none.mode("payment"));
        assertTrue(none.internal().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> none.mode("missing"));
    }

    @Test
    void ac_mode_02_a_known_module_without_a_mode_fails_with_tl0001_for_every_such_module() {
        TopolithException failure = fails(List.of(user, payment), List.of(), modes("user", "internal"), null);
        assertEquals(List.of(TopolithCode.TL0001), codes(failure));
        assertTrue(failure.getMessage().contains("'payment'"), failure.getMessage());
        assertEquals(List.of(TopolithCode.TL0001, TopolithCode.TL0001),
                codes(fails(List.of(user, payment), List.of(), modes(), null)));
        // a key holding only other settings is not a mode
        Map<String, String> noMode = modes();
        noMode.put("user", null);
        assertEquals(List.of(TopolithCode.TL0001, TopolithCode.TL0001),
                codes(fails(List.of(user, payment), List.of(), noMode, null)));
    }

    @Test
    void ac_mode_02_a_module_known_only_through_its_api_descriptor_needs_a_mode() {
        TopolithException failure = fails(List.of(user), List.of(api("billing", "com.acme.billing")),
                modes("user", "internal"), null);
        assertEquals(List.of(TopolithCode.TL0001), codes(failure));
        assertTrue(failure.getMessage().contains("'billing'"), failure.getMessage());
        Topology topology = Topology.resolve(List.of(user), List.of(api("billing", "com.acme.billing")),
                modes("user", "internal", "billing", "external"), null);
        assertEquals(Mode.EXTERNAL, topology.mode("billing"));
        assertEquals(Set.of("com.acme.billing", "com.acme.userapi"), topology.apiPackages());
    }

    @Test
    void ac_mode_03_a_standalone_module_defaults_only_itself_to_internal() {
        Topology topology = Topology.resolve(List.of(user), List.of(), modes(), "user");
        assertEquals(Mode.INTERNAL, topology.mode("user"));
        TopolithException failure = fails(List.of(user, payment), List.of(), modes(), "user");
        assertEquals(List.of(TopolithCode.TL0001), codes(failure));
        assertTrue(failure.getMessage().contains("'payment'"), failure.getMessage());
        assertEquals(Mode.NONE, Topology.resolve(List.of(user), List.of(), modes("user", "none"), "user")
                .mode("user"));
    }

    @Test
    void ac_mode_03_a_standalone_id_without_a_module_descriptor_fails_with_tl0002() {
        TopolithException failure = fails(List.of(user), List.of(api("billing", "com.acme.billing")),
                modes("user", "none", "billing", "none"), "billing");
        assertEquals(List.of(TopolithCode.TL0002), codes(failure));
        assertEquals(List.of(TopolithCode.TL0002), codes(fails(List.of(user), List.of(), modes("user", "none"),
                "ghost")));
    }

    @Test
    void ac_mode_03_a_standalone_id_that_is_also_declared_internal_is_reported_once() {
        TopolithException failure = fails(List.of(user), List.of(), modes("user", "none", "ghost", "internal"),
                "ghost");
        assertEquals(List.of(TopolithCode.TL0002), codes(failure));
    }

    @Test
    void ac_mode_05_internal_without_a_module_descriptor_fails_with_tl0002_and_a_fix() {
        TopolithException failure = fails(List.of(), List.of(api("user", "com.acme.user")), modes("user", "internal"),
                null);
        assertEquals(List.of(TopolithCode.TL0002), codes(failure));
        assertTrue(failure.getMessage().contains("META-INF/topolith/modules/user.properties"), failure.getMessage());
        assertTrue(failure.diagnostics().get(0).action().contains("external or none"));
    }

    @Test
    void ac_mode_08_an_internal_module_requiring_a_none_or_missing_module_fails_with_tl0004() {
        TopolithException failure = fails(List.of(user, payment), List.of(), modes("user", "none", "payment",
                "internal"), null);
        assertEquals(List.of(TopolithCode.TL0004), codes(failure));
        assertTrue(failure.getMessage().contains("'payment' requires 'user'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("its mode is none"), failure.getMessage());
        TopolithException absent = fails(List.of(payment), List.of(), modes("payment", "internal"), null);
        assertEquals(List.of(TopolithCode.TL0004), codes(absent));
        assertTrue(absent.getMessage().contains("not on the classpath"), absent.getMessage());
    }

    @Test
    void ac_mode_16_an_external_module_is_available_and_its_own_requires_are_not_checked() {
        Topology topology = Topology.resolve(List.of(user, payment), List.of(), modes("user", "none", "payment",
                "external"), null);
        assertEquals(Mode.EXTERNAL, topology.mode("payment"));
        Topology required = Topology.resolve(List.of(user, payment), List.of(), modes("user", "external",
                "payment", "internal"), null);
        assertEquals(Set.of("payment"), required.internal());
    }

    @Test
    void ac_mode_21_a_mode_for_an_unknown_id_fails_with_tl0016_or_tl0002_when_internal() {
        TopolithException failure = fails(List.of(user), List.of(), modes("user", "none", "usr", "none"), null);
        assertEquals(List.of(TopolithCode.TL0016), codes(failure));
        assertTrue(failure.getMessage().contains("'usr'"), failure.getMessage());
        assertEquals(List.of(TopolithCode.TL0016), codes(fails(List.of(user), List.of(),
                modes("user", "none", "usr", "bogus"), null)));
        assertEquals(List.of(TopolithCode.TL0002), codes(fails(List.of(user), List.of(),
                modes("user", "none", "usr", "internal"), null)));
        Map<String, String> onlyOtherKeys = modes("user", "none");
        onlyOtherKeys.put("usr", null);
        assertEquals(Set.of("user"), Topology.resolve(List.of(user), List.of(), onlyOtherKeys, null).known());
    }

    @Test
    void ac_mode_22_an_invalid_or_blank_mode_fails_with_tl0001() {
        for (String value : List.of("loop back", "", "  ", "intern al")) {
            TopolithException failure = fails(List.of(user), List.of(), modes("user", value), null);
            assertEquals(List.of(TopolithCode.TL0001), codes(failure), value);
            assertTrue(failure.getMessage().contains("invalid mode"), failure.getMessage());
        }
        assertEquals(java.util.Optional.empty(), Mode.parse(null));
        assertEquals(java.util.Optional.of(Mode.NONE), Mode.parse("None"));
    }

    @Test
    void a_stage_reports_every_problem_and_stops_before_the_next_stage() {
        TopolithException modeStage = fails(List.of(user, payment), List.of(), modes("payment", "internal",
                "ghost", "none"), null);
        assertEquals(List.of(TopolithCode.TL0016, TopolithCode.TL0001), codes(modeStage).stream()
                .sorted(java.util.Comparator.comparing(code -> code == TopolithCode.TL0001)).toList());
        assertTrue(!codes(modeStage).contains(TopolithCode.TL0004));
        ModuleDescriptor a = module("a", "com.a", "b");
        ModuleDescriptor b = module("b", "com.b", "a");
        assertEquals(List.of(TopolithCode.TL0008), codes(fails(List.of(a, b), List.of(), modes(), null)));
    }

    @Test
    void ac_mode_04_owner_of_class_follows_the_module_package() {
        Topology topology = Topology.resolve(List.of(user, payment), List.of(), modes("user", "none", "payment",
                "none"), null);
        assertEquals(java.util.Optional.of("user"), topology.ownerOfClass("com.acme.user.sub.Thing"));
        assertEquals(java.util.Optional.empty(), topology.ownerOfClass("com.acme.userother.Thing"));
        assertEquals(java.util.Optional.empty(), topology.ownerOfClass("com.acme.Host"));
        assertEquals(Map.of("payment", "com.acme.payment", "user", "com.acme.user"), topology.modulePackages());
    }

    @Test
    void ac_desc_10_nested_or_equal_module_packages_fail_with_tl0017_naming_both() {
        ModuleDescriptor inner = ModuleDescriptor.builder("inner").packageName("com.acme.user.inner")
                .apiPackages(List.of("com.acme.innerapi")).apiVersion("1").build();
        TopolithException nested = fails(List.of(user, inner), List.of(), modes(), null);
        assertEquals(List.of(TopolithCode.TL0017), codes(nested));
        assertTrue(nested.getMessage().contains("'inner'") && nested.getMessage().contains("'user'"),
                nested.getMessage());
        ModuleDescriptor same = ModuleDescriptor.builder("same").packageName("com.acme.user")
                .apiPackages(List.of("com.acme.sameapi")).apiVersion("1").build();
        assertEquals(List.of(TopolithCode.TL0017), codes(fails(List.of(same, user), List.of(), modes(), null)));
        assertEquals(List.of(TopolithCode.TL0017), codes(fails(List.of(inner, user), List.of(), modes(), null)));
    }

    @Test
    void ac_desc_10_a_foreign_api_package_in_a_module_package_fails_but_the_modules_own_api_is_allowed() {
        ModuleDescriptor own = ModuleDescriptor.builder("own").packageName("com.acme.own")
                .apiPackages(List.of("com.acme.own.api")).apiVersion("1").build();
        assertEquals(Mode.NONE, Topology.resolve(List.of(own), List.of(api("own", "com.acme.own.api")),
                modes("own", "none"), null).mode("own"));
        TopolithException foreign = fails(List.of(own), List.of(api("billing", "com.acme.own.billing")), modes(),
                null);
        assertEquals(List.of(TopolithCode.TL0017), codes(foreign));
        assertTrue(foreign.getMessage().contains("'billing'") && foreign.getMessage().contains("'own'"),
                foreign.getMessage());
        ModuleDescriptor other = module("other", "com.acme.other", "own");
        TopolithException fromModule = fails(List.of(own, ModuleDescriptor.builder("other")
                .packageName("com.acme.other").apiPackages(List.of("com.acme.own")).apiVersion("1").build()),
                List.of(), modes(), null);
        assertEquals(List.of(TopolithCode.TL0017), codes(fromModule));
        assertEquals(Mode.NONE, Topology.resolve(List.of(own, other), List.of(), modes("own", "none", "other",
                "none"), null).mode("other"));
    }

    @Test
    void duplicate_ids_are_tl0006_before_any_nesting_check() {
        ModuleDescriptor twin = module("user", "com.acme.user.twin");
        assertEquals(List.of(TopolithCode.TL0006), codes(fails(List.of(user, twin), List.of(), modes(), null)));
        assertEquals(List.of(TopolithCode.TL0006), codes(fails(List.of(user), List.of(api("billing", "x.y"),
                api("billing", "x.z")), modes(), null)));
    }
    @Test
    void ac_mode_39_loopback_parses_trimmed_and_case_insensitive_and_needs_a_module_descriptor() {
        assertEquals(Mode.LOOPBACK, Mode.parse(" LoopBack ").orElseThrow());
        Topology topology = Topology.resolve(List.of(user, payment), List.of(), modes("user", "internal", "payment",
                " LOOPBACK "), null);
        assertEquals(Mode.LOOPBACK, topology.mode("payment"));
        assertEquals(Set.of("payment"), topology.idsOf(Mode.LOOPBACK));
        assertEquals(Set.of("user"), topology.idsOf(Mode.INTERNAL));
        assertEquals(Set.of("payment", "user"), topology.internal());
        TopolithException failure = fails(List.of(), List.of(api("user", "com.acme.user")), modes("user", "loopback"),
                null);
        assertEquals(List.of(TopolithCode.TL0002), codes(failure));
        assertTrue(failure.getMessage().contains("is loopback"), failure.getMessage());
        assertEquals(List.of(TopolithCode.TL0002), codes(fails(List.of(user), List.of(),
                modes("user", "none", "usr", "loopback"), null)));
    }

    @Test
    void ac_mode_51_a_loopback_module_with_an_unmet_requires_fails_with_tl0004() {
        TopolithException failure = fails(List.of(user, payment), List.of(), modes("user", "none", "payment",
                "loopback"), null);
        assertEquals(List.of(TopolithCode.TL0004), codes(failure));
        assertTrue(failure.getMessage().contains("loopback module 'payment' requires 'user'"), failure.getMessage());
    }

    @Test
    void ac_mode_52_an_internal_module_requiring_a_loopback_module_starts() {
        Topology topology = Topology.resolve(List.of(user, payment), List.of(), modes("user", "loopback", "payment",
                "internal"), null);
        assertEquals(Mode.LOOPBACK, topology.mode("user"));
        assertEquals(Mode.INTERNAL, topology.mode("payment"));
    }

    @Test
    void ac_mode_41_a_loopback_module_and_its_matching_api_are_not_two_providers() {
        ModuleDescriptor provider = ModuleDescriptor.builder("pay").packageName("com.acme.pay")
                .apiPackages(List.of("com.acme.pay.api")).provides(List.of("com.acme.pay.api.Pay")).apiVersion("1.0.0")
                .source("jar:/pay.jar!/pay.properties").build();
        ApiDescriptor api = ApiDescriptor.builder("pay").apiPackages(List.of("com.acme.pay.api"))
                .provides(List.of("com.acme.pay.api.Pay")).apiVersion("1.0.0")
                .source("jar:/pay-api.jar!/pay.properties").build();
        assertEquals(Mode.LOOPBACK, Topology.resolve(List.of(provider), List.of(api), modes("pay", "loopback"), null)
                .mode("pay"));
    }
}
