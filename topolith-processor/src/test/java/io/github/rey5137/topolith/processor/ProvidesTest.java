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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code @ConsumerImplemented} marker and the module declaration's {@code provides} (D-110). */
class ProvidesTest {
    private static final String LISTENER = "com.acme.user.Events$Listener";
    private static final String MARKED = "import io.github.rey5137.topolith.contract.ConsumerImplemented;\n";

    @TempDir
    Path work;
    private int builds;

    private Javac.Result api(String declaration, String... extra) throws IOException {
        return Javac.compile(work.resolve("api" + builds++), Fixtures.api(declaration, extra), List.of(Javac.core()),
                Javac.VERSION);
    }

    /** The module built against the API as a JAR, as a separate API artifact is. */
    private Javac.Result module(Javac.Result api, String declaration, String... extra) throws IOException {
        assertTrue(api.success(), api.errors());
        Path jar = Javac.jar(api.output(), work.resolve("user-api" + builds++ + ".jar"));
        return Javac.compile(work.resolve("module" + builds++), Fixtures.module(declaration, extra),
                List.of(Javac.core(), jar), Javac.VERSION);
    }

    /** The API and the module in one compilation, as a single artifact is. */
    private Javac.Result single(String[] api, String declaration, String... extra) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(Fixtures.api("", api));
        sources.putAll(Fixtures.module(declaration, extra));
        return Javac.compile(work.resolve("single" + builds++), sources, List.of(Javac.core()), Javac.VERSION);
    }

    private static String listing(String... contracts) {
        return "@TopolithModule(id = \"user\", api = \"com.acme.user\", provides = {\""
                + String.join("\", \"", contracts) + "\"})";
    }

    private static final String[] SPI = {
        "com/acme/user/Plugin.java", "package com.acme.user;\n" + MARKED
                + "@ConsumerImplemented public interface Plugin { void run(); }\n"};

    private static final String[] PLUGIN_IMPL = {
        "com/acme/user/impl/PluginImpl.java",
        "package com.acme.user.impl;\nclass PluginImpl implements com.acme.user.Plugin { public void run() { } }\n"};

    @Test
    void ac_desc_14_a_marked_interface_is_in_neither_provides_and_its_implementer_builds() throws IOException {
        Javac.Result apis = api(Fixtures.API, SPI);
        assertTrue(apis.success(), apis.errors());
        Set<String> expected = Set.of("com.acme.user.UserService", LISTENER);
        assertEquals(expected, apis.api("user").provides());

        Javac.Result result = module(apis, Fixtures.MODULE, PLUGIN_IMPL);
        assertTrue(result.success(), result.errors());
        assertEquals(expected, result.module("user").provides());

        Javac.Result together = single(SPI, Fixtures.MODULE, PLUGIN_IMPL);
        assertTrue(together.success(), together.errors());
        assertEquals(expected, together.module("user").provides());
        assertEquals(expected, together.api("user").provides());
    }

    @Test
    void ac_desc_14_an_unmarked_supertype_stays_a_contract_on_both_sides_unless_marked_too() throws IOException {
        String hook = "package com.acme.user;\n" + MARKED
                + "@ConsumerImplemented public interface Hook extends Base { void after(); }\n";
        String[] impl = {"com/acme/user/impl/HookImpl.java", "package com.acme.user.impl;\n"
                + "class HookImpl implements com.acme.user.Hook {\n"
                + "    public void before() { }\n    public void after() { }\n}\n"};
        Javac.Result apis = api(Fixtures.API, "com/acme/user/Hook.java", hook, "com/acme/user/Base.java",
                "package com.acme.user;\npublic interface Base { void before(); }\n");
        assertTrue(apis.success(), apis.errors());
        Set<String> expected = Set.of("com.acme.user.Base", "com.acme.user.UserService", LISTENER);
        assertEquals(expected, apis.api("user").provides());
        Javac.Result result = module(apis, Fixtures.MODULE, impl);
        assertTrue(result.success(), result.errors());
        assertEquals(expected, result.module("user").provides());

        String[] bothMarked = {"com/acme/user/Hook.java", hook, "com/acme/user/Base.java", "package com.acme.user;\n"
                + MARKED + "@ConsumerImplemented public interface Base { void before(); }\n"};
        Javac.Result marked = single(bothMarked, Fixtures.MODULE, impl);
        assertTrue(marked.success(), marked.errors());
        assertEquals(Set.of("com.acme.user.UserService", LISTENER), marked.api("user").provides());
        assertEquals(marked.api("user").provides(), marked.module("user").provides());
    }

    private static final String[] TICKER = {
        "com/acme/user/Clock.java", "package com.acme.user;\npublic interface Clock { String now(); }\n",
        "com/acme/user/Events.java", "package com.acme.user;\npublic class Events { public interface Listener "
                + "{ void on(); } public interface Ticker { void tick(); } }\n"};

    @Test
    void ac_desc_15_a_listed_contract_without_an_implementing_class_is_in_the_module_descriptor() throws IOException {
        Set<String> expected = Set.of("com.acme.user.Clock", "com.acme.user.Events$Ticker",
                "com.acme.user.UserService", LISTENER);
        String declaration = listing("com.acme.user.Clock", "com.acme.user.Events$Ticker");
        Javac.Result apis = api(Fixtures.API, TICKER);
        assertTrue(apis.success(), apis.errors());
        assertEquals(expected, apis.api("user").provides());
        Javac.Result result = module(apis, declaration);
        assertTrue(result.success(), result.errors());
        assertEquals(expected, result.module("user").provides());

        Javac.Result together = single(TICKER, declaration);
        assertTrue(together.success(), together.errors());
        assertEquals(expected, together.module("user").provides());
        assertEquals(expected, together.api("user").provides());
    }

    @Test
    void ac_desc_15_without_the_listing_a_bean_only_contract_still_fails_the_build() throws IOException {
        Javac.Result result = module(api(Fixtures.API, TICKER), listing("com.acme.user.Clock"));
        assertFalse(result.success());
        assertTrue(result.errors().contains("com.acme.user.Events$Ticker but no class in com.acme.user.impl "
                + "implements it") && result.errors().contains("list it in the module declaration's provides"),
                result.errors());
    }

    @Test
    void ac_desc_15_an_entry_outside_the_api_provides_fails_the_build() throws IOException {
        Javac.Result apis = api(Fixtures.API, TICKER[0], TICKER[1], TICKER[2], TICKER[3], SPI[0], SPI[1]);
        for (String entry : List.of("com.acme.user.Crud", "com.acme.user.Missing", "com.acme.user.Events.Ticker",
                "com.acme.user.Plugin")) {
            Javac.Result result = module(apis, listing("com.acme.user.Clock",
                    "com.acme.user.Events$Ticker", entry));
            assertFalse(result.success(), entry);
            assertTrue(result.errors().contains("TL0013") && result.errors().contains("lists " + entry
                    + " in provides, but the API's provides do not include it"), result.errors());
            assertFalse(Files.exists(result.file("META-INF/topolith/modules/user.properties")));
        }
    }

    @Test
    void ac_desc_15_an_entry_with_an_implementing_class_fails_the_build() throws IOException {
        Javac.Result result = single(new String[0], listing("com.acme.user.UserService"));
        assertFalse(result.success());
        assertTrue(result.errors().contains("TL0013") && result.errors().contains("lists com.acme.user.UserService "
                + "in provides, but a class in com.acme.user.impl implements it"), result.errors());
    }

    @Test
    void ac_desc_15_provides_on_an_api_declaration_or_a_module_without_api_fails_the_build() throws IOException {
        Javac.Result apiSide = api("@TopolithModule(id = \"user\", api = \"com.acme.user\", "
                + "provides = \"com.acme.user.UserService\")");
        assertFalse(apiSide.success());
        assertTrue(apiSide.errors().contains("TL0013") && apiSide.errors().contains("sets provides"),
                apiSide.errors());
        assertFalse(Files.exists(apiSide.file("META-INF/topolith/apis/user.properties")));

        Javac.Result noApi = Javac.compile(work.resolve("job"), Map.of("com/acme/job/package-info.java",
                "@TopolithModule(id = \"job\", provides = \"com.acme.user.UserService\")\npackage com.acme.job;\n"
                        + "import io.github.rey5137.topolith.annotation.TopolithModule;\n"),
                List.of(Javac.core()), Javac.VERSION);
        assertFalse(noApi.success());
        assertTrue(noApi.errors().contains("TL0013") && noApi.errors().contains("has no api packages"),
                noApi.errors());
    }
}
