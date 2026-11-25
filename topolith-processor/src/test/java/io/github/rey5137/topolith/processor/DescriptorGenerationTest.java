package io.github.rey5137.topolith.processor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.ModuleDescriptor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DescriptorGenerationTest {
    private static final String LISTENER = "com.acme.user.Events$Listener";

    @TempDir
    Path work;

    private Javac.Result api(String declaration, String... extra) throws IOException {
        return Javac.compile(work.resolve("api"), Fixtures.api(declaration, extra), List.of(Javac.core()),
                Javac.VERSION);
    }

    private Javac.Result module(Path classpathEntry, String declaration, String... extra) throws IOException {
        return Javac.compile(work.resolve("module"), Fixtures.module(declaration, extra),
                List.of(Javac.core(), classpathEntry), Javac.VERSION);
    }

    private Javac.Result module(String declaration, String... extra) throws IOException {
        Javac.Result api = api(Fixtures.API);
        assertTrue(api.success(), api.errors());
        return module(api.output(), declaration, extra);
    }

    private static String declaration(String requires, String optional) {
        return "@TopolithModule(id = \"user\", api = \"com.acme.user\", requires = {" + requires + "}, optional = {"
                + optional + "})";
    }

    @Test
    void ac_desc_01_module_declaration_emits_the_module_descriptor() throws IOException {
        Javac.Result result = module(Fixtures.MODULE);
        assertTrue(result.success(), result.errors());
        assertTrue(Files.isRegularFile(result.file("META-INF/topolith/modules/user.properties")));
        assertFalse(Files.exists(result.file("META-INF/topolith/apis/user.properties")));
    }

    @Test
    void ac_desc_01_a_compilation_without_a_declaration_emits_nothing() throws IOException {
        Javac.Result result = Javac.compile(work, Map.of("a/B.java", "package a;\npublic class B { }\n"),
                List.of(Javac.core()));
        assertTrue(result.success(), result.errors());
        assertFalse(Files.exists(result.file("META-INF")));
    }

    @Test
    void ac_desc_02_descriptor_records_id_packages_requires_and_optional() throws IOException {
        Javac.Result result = module(declaration("\"billing\", \"audit\"", "\"search\""));
        assertTrue(result.success(), result.errors());
        ModuleDescriptor expected = ModuleDescriptor.builder("user").apiPackages(List.of("com.acme.user"))
                .provides(List.of(LISTENER, "com.acme.user.UserService")).requires(List.of("audit", "billing"))
                .optional(List.of("search")).apiVersion("1.4.2").build();
        assertEquals(expected, result.module("user"));
    }

    @Test
    void ac_desc_02_several_api_packages_are_all_recorded() throws IOException {
        String both = "@TopolithModule(id = \"user\", api = {\"com.acme.user\", \"com.acme.user.spi\"})";
        Javac.Result apis = api(both, "com/acme/user/spi/Hook.java",
                "package com.acme.user.spi;\npublic interface Hook { }\n");
        assertTrue(apis.success(), apis.errors());
        Javac.Result result = module(apis.output(), both, "com/acme/user/impl/HookImpl.java",
                "package com.acme.user.impl;\nclass HookImpl implements com.acme.user.spi.Hook { }\n");
        assertTrue(result.success(), result.errors());
        assertEquals(Set.of("com.acme.user", "com.acme.user.spi"), result.module("user").apiPackages());
        assertEquals(Set.of("com.acme.user", "com.acme.user.spi"), apis.api("user").apiPackages());
    }

    @Test
    void ac_desc_04_implemented_interfaces_are_provided_and_others_are_not() throws IOException {
        Javac.Result result = module(Fixtures.MODULE);
        assertTrue(result.success(), result.errors());
        // Crud is a superinterface of UserService, so only UserService stays; Shape, Marker and Hidden are no
        // candidates and are not implemented in the module.
        assertEquals(Set.of("com.acme.user.UserService", LISTENER), result.module("user").provides());
    }

    @Test
    void ac_desc_04_inference_follows_abstract_superclasses_enums_and_records() throws IOException {
        Javac.Result apis = api(Fixtures.API,
                "com/acme/user/Ping.java", "package com.acme.user;\npublic interface Ping { }\n",
                "com/acme/user/Mode.java", "package com.acme.user;\npublic interface Mode { }\n",
                "com/acme/user/Tag.java", "package com.acme.user;\npublic interface Tag { }\n");
        assertTrue(apis.success(), apis.errors());
        Javac.Result result = module(apis.output(), Fixtures.MODULE,
                "com/acme/user/impl/Base.java",
                "package com.acme.user.impl;\nabstract class Base implements com.acme.user.Ping { }\n",
                "com/acme/user/impl/Concrete.java", "package com.acme.user.impl;\nclass Concrete extends Base { }\n",
                "com/acme/user/impl/Kind.java",
                "package com.acme.user.impl;\nenum Kind implements com.acme.user.Mode { A }\n",
                "com/acme/user/impl/Label.java",
                "package com.acme.user.impl;\nrecord Label() implements com.acme.user.Tag { }\n");
        assertTrue(result.success(), result.errors());
        assertEquals(Set.of("com.acme.user.UserService", LISTENER, "com.acme.user.Ping", "com.acme.user.Mode",
                "com.acme.user.Tag"),
                result.module("user").provides());
    }

    @Test
    void ac_desc_04_an_unimplemented_candidate_fails_the_build() throws IOException {
        Javac.Result apis = api(Fixtures.API,
                "com/acme/user/Audit.java", "package com.acme.user;\npublic interface Audit { }\n");
        assertTrue(apis.success(), apis.errors());
        Javac.Result result = module(Javac.jar(apis.output(), work.resolve("user-api.jar")), Fixtures.MODULE);
        assertFalse(result.success());
        assertTrue(result.errors().contains("TL0013"), result.errors());
        assertTrue(result.errors().contains("com.acme.user.Audit but no class in com.acme.user.impl implements it"),
                result.errors());
        assertFalse(Files.exists(result.file("META-INF/topolith/modules/user.properties")));
    }

    @Test
    void ac_desc_04_a_class_outside_the_module_package_warns() throws IOException {
        Javac.Result result = module(Fixtures.MODULE, "com/acme/other/Stray.java",
                "package com.acme.other;\nclass Stray implements com.acme.user.UserService {"
                        + " public String find() { return null; } public void rename() { } }\n");
        assertTrue(result.success(), result.errors());
        assertTrue(result.messages(javax.tools.Diagnostic.Kind.WARNING).contains("com.acme.other.Stray"));
    }

    @Test
    void ac_desc_04_single_artifact_infers_and_verifies_against_its_own_api() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(Fixtures.api(""));
        sources.putAll(Fixtures.module(Fixtures.MODULE));
        Javac.Result result = Javac.compile(work, sources, List.of(Javac.core()), Javac.VERSION);
        assertTrue(result.success(), result.errors());
        assertEquals(result.module("user").provides(), result.api("user").provides());
        assertEquals(Set.of("com.acme.user.UserService", LISTENER), result.api("user").provides());

        sources.remove("com/acme/user/impl/ListenerImpl.java");
        Javac.Result missing = Javac.compile(work.resolve("again"), sources, List.of(Javac.core()), Javac.VERSION);
        assertFalse(missing.success());
        assertTrue(missing.errors().contains(LISTENER), missing.errors());
    }

    @Test
    void ac_desc_06_api_declaration_emits_all_four_fields() throws IOException {
        Javac.Result result = api(Fixtures.API);
        assertTrue(result.success(), result.errors());
        ApiDescriptor expected = ApiDescriptor.builder("user").apiPackages(List.of("com.acme.user"))
                .provides(List.of(LISTENER, "com.acme.user.UserService")).apiVersion("1.4.2").build();
        assertEquals(expected, result.api("user"));
        assertFalse(Files.exists(result.file("META-INF/topolith/modules/user.properties")));
        String text = Files.readString(result.file("META-INF/topolith/apis/user.properties"));
        for (String key : List.of("id=", "api-packages=", "provides=", "api-version=")) {
            assertTrue(text.contains(key), text);
        }
    }

    @Test
    void ac_desc_04_a_partial_compile_of_a_single_artifact_module_still_verifies() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(Fixtures.api(""));
        sources.putAll(Fixtures.module(Fixtures.MODULE));
        Javac.Result full = Javac.compile(work, sources, List.of(Javac.core()), Javac.VERSION);
        assertTrue(full.success(), full.errors());
        Files.delete(full.file("META-INF/topolith/modules/user.properties"));
        Files.delete(full.file("META-INF/topolith/apis/user.properties"));

        // The IDE recompiles only the edited package-info; every api and module class is already in the output.
        Javac.Result partial = Javac.compile(work, Map.of("com/acme/user/impl/package-info.java",
                sources.get("com/acme/user/impl/package-info.java")), List.of(Javac.core(), full.output()),
                Javac.VERSION);
        assertTrue(partial.success(), partial.errors());
        assertEquals(Set.of("com.acme.user.UserService", LISTENER), partial.module("user").provides());
        assertEquals(partial.module("user").provides(), partial.api("user").provides());
    }

    @Test
    void ac_desc_06_an_api_package_split_with_the_api_jar_fails_the_module_build() throws IOException {
        Javac.Result apis = api(Fixtures.API);
        assertTrue(apis.success(), apis.errors());
        Javac.Result result = module(Javac.jar(apis.output(), work.resolve("user-api.jar")), Fixtures.MODULE,
                "com/acme/user/UserServices.java", "package com.acme.user;\npublic final class UserServices { }\n");
        assertFalse(result.success());
        assertTrue(result.errors().contains("TL0013") && result.errors().contains("com.acme.user' is split"),
                result.errors());
        assertFalse(result.errors().contains("Rebuild the API artifact"), result.errors());
        assertFalse(Files.exists(result.file("META-INF/topolith")));
    }

    /** Spike result (JDK 21): the annotation and the package members are read from a JAR, unlike resources. */
    @Test
    void ac_desc_06_the_module_build_verifies_against_an_api_jar() throws IOException {
        Javac.Result apis = api(Fixtures.API);
        assertTrue(apis.success(), apis.errors());
        Path jar = Javac.jar(apis.output(), work.resolve("user-api.jar"));
        Javac.Result fromJar = module(jar, Fixtures.MODULE);
        assertTrue(fromJar.success(), fromJar.errors());
        assertEquals(module(apis.output(), Fixtures.MODULE).module("user"), fromJar.module("user"));
    }

    @Test
    void ac_desc_06_a_jar_without_the_api_declaration_fails_the_module_build() throws IOException {
        Javac.Result apis = api("");
        assertTrue(apis.success(), apis.errors());
        Javac.Result result = module(Javac.jar(apis.output(), work.resolve("user-api.jar")), Fixtures.MODULE);
        assertFalse(result.success());
        assertTrue(result.errors().contains("TL0013") && result.errors().contains("@TopolithModule declaration"),
                result.errors());
        assertFalse(Files.exists(result.file("META-INF/topolith/modules/user.properties")));
    }

    @Test
    void ac_desc_06_a_different_id_or_api_list_in_the_jar_fails_the_module_build() throws IOException {
        Javac.Result otherId = api("@TopolithModule(id = \"other\", api = \"com.acme.user\")");
        assertTrue(otherId.success(), otherId.errors());
        Javac.Result idResult = module(Javac.jar(otherId.output(), work.resolve("other.jar")), Fixtures.MODULE);
        assertFalse(idResult.success());
        assertTrue(idResult.errors().contains("has id 'other'"), idResult.errors());

        Javac.Result wider = Javac.compile(work.resolve("wide"), Fixtures.api(
                "@TopolithModule(id = \"user\", api = {\"com.acme.user\", \"com.acme.extra\"})",
                "com/acme/extra/Extra.java", "package com.acme.extra;\npublic class Extra { }\n"),
                List.of(Javac.core()), Javac.VERSION);
        assertTrue(wider.success(), wider.errors());
        Javac.Result listResult = module(Javac.jar(wider.output(), work.resolve("wide.jar")), Fixtures.MODULE);
        assertFalse(listResult.success());
        assertTrue(listResult.errors().contains("api [com.acme.extra, com.acme.user] but the module declares"),
                listResult.errors());
    }

    @Test
    void ac_desc_06_api_declaration_rejects_requires_and_missing_packages() throws IOException {
        Javac.Result requires = api("@TopolithModule(id = \"user\", api = \"com.acme.user\", requires = \"billing\")");
        assertFalse(requires.success());
        assertTrue(requires.errors().contains("sets requires or optional"), requires.errors());

        Javac.Result typo = api("@TopolithModule(id = \"user\", api = {\"com.acme.user\", \"com.acme.usre\"})");
        assertFalse(typo.success());
        assertTrue(typo.errors().contains("com.acme.usre"), typo.errors());
    }

    @Test
    void ac_desc_01_declaration_problems_are_compile_errors_not_exceptions() throws IOException {
        Javac.Result badId = api("@TopolithModule(id = \"Bad_Id\", api = \"com.acme.user\")");
        assertFalse(badId.success());
        assertTrue(badId.errors().contains("TL0013") && badId.errors().contains("Bad_Id"), badId.errors());

        Javac.Result typo = module("@TopolithModule(id = \"user\", api = \"com.acme.nothing\")");
        assertFalse(typo.success());
        assertTrue(typo.errors().contains("com.acme.nothing"), typo.errors());

        Javac.Result self = module("@TopolithModule(id = \"user\", api = \"com.acme.user\", requires = \"user\")");
        assertFalse(self.success());
        assertTrue(self.errors().contains("lists itself"), self.errors());

        Javac.Result mixed = Javac.compile(work.resolve("mixed"), Fixtures.api(
                "@TopolithModule(id = \"user\", api = {\"com.acme.user\", \"com.acme.other\"})"),
                List.of(Javac.core()), Javac.VERSION);
        assertFalse(mixed.success());
        assertTrue(mixed.errors().contains("com.acme.other"), mixed.errors());
    }

    @Test
    void ac_desc_01_two_declarations_in_one_compilation_fail() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(Fixtures.api(Fixtures.API));
        sources.put("com/acme/second/package-info.java", "@io.github.rey5137.topolith.annotation.TopolithModule("
                + "id = \"second\", api = \"com.acme.user\")\npackage com.acme.second;\n");
        Javac.Result result = Javac.compile(work, sources, List.of(Javac.core()), Javac.VERSION);
        assertFalse(result.success());
        assertTrue(result.errors().contains("2 @TopolithModule declarations"), result.errors());
    }

    @Test
    void ac_desc_02_output_is_deterministic_and_has_no_timestamp() throws IOException {
        Javac.Result first = module(declaration("\"b\", \"a\"", ""));
        byte[] before = Files.readAllBytes(first.file("META-INF/topolith/modules/user.properties"));
        Javac.Result second = Javac.compile(work.resolve("second"), Fixtures.module(declaration("\"a\", \"b\"", "")),
                List.of(Javac.core(), work.resolve("api/out")), Javac.VERSION);
        assertTrue(second.success(), second.errors());
        assertArrayEquals(before, Files.readAllBytes(second.file("META-INF/topolith/modules/user.properties")));
        assertFalse(new String(before).contains("#"));
    }
}
