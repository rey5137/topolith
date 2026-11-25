package io.github.rey5137.topolith.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DescriptorsTest {
    private static final String SOURCE = "jar:file:/orders.jar!/META-INF/topolith/modules/orders.properties";
    private static final String VALID = """
            format-version=1
            id=orders
            api-packages=com.acme.orders
            api-version=1.2.0
            provides=com.acme.orders.OrderApi
            requires=billing
            optional=search
            """;

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ModuleDescriptor parse(String text) {
        return Descriptors.parseModule(SOURCE, stream(text));
    }

    private static TopolithException failure(String text) {
        TopolithException failure = assertThrows(TopolithException.class, () -> parse(text));
        failure.diagnostics().forEach(diagnostic -> assertEquals(TopolithCode.TL0013, diagnostic.code()));
        return failure;
    }

    private static void assertFails(String text, String... fragments) {
        String message = failure(text).getMessage();
        assertTrue(message.contains(SOURCE), message);
        for (String fragment : fragments) {
            assertTrue(message.contains(fragment), "expected '" + fragment + "' in: " + message);
        }
    }

    @Test
    void ac_desc_07_parsing_preserves_identity_api_metadata_and_edges() {
        ModuleDescriptor module = parse(VALID);
        assertEquals("orders", module.id());
        assertEquals(Set.of("com.acme.orders"), module.apiPackages());
        assertEquals(Set.of("com.acme.orders.OrderApi"), module.provides());
        assertEquals("1.2.0", module.apiVersion());
        assertEquals(Set.of("billing"), module.requires());
        assertEquals(Set.of("search"), module.optional());
        assertEquals(SOURCE, module.source());
        assertEquals("orders", module.api().id());
        assertEquals(1, module.api().formatVersion());
    }

    @Test
    void ac_desc_07_an_api_descriptor_keeps_its_four_fields() {
        String source = "jar:file:/orders-api.jar!/META-INF/topolith/apis/orders.properties";
        ApiDescriptor api = Descriptors.parseApi(source, stream("""
                format-version=1
                id=orders
                api-packages=com.acme.orders
                api-version=1.2.0
                provides=com.acme.orders.OrderApi
                """));
        assertEquals("orders", api.id());
        assertEquals(Set.of("com.acme.orders"), api.apiPackages());
        assertEquals("1.2.0", api.apiVersion());
        assertEquals(Set.of("com.acme.orders.OrderApi"), api.provides());
        assertEquals(source, api.source());
    }

    @Test
    void ac_desc_07_lists_keep_declared_order_and_are_unmodifiable() {
        ModuleDescriptor module = parse(VALID.replace("requires=billing", "requires=zeta, alpha ,,mid,"));
        assertEquals(List.of("zeta", "alpha", "mid"), List.copyOf(module.requires()));
        assertThrows(UnsupportedOperationException.class, () -> module.requires().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> module.apiPackages().add("x"));
    }

    @Test
    void missing_or_empty_optional_lists_mean_none() {
        ModuleDescriptor module = parse("""
                format-version=1
                id=orders
                api-packages=com.acme.orders
                api-version=1.0
                requires=
                """);
        assertTrue(module.requires().isEmpty());
        assertTrue(module.optional().isEmpty());
        assertTrue(module.provides().isEmpty());
    }

    @Test
    void round_trip_write_then_parse_is_equal() throws IOException {
        ModuleDescriptor module = ModuleDescriptor.builder("order-history")
                .apiPackages(List.of("com.acme.history", "com.acme.history.events"))
                .provides(List.of("com.acme.history.HistoryApi")).requires(List.of("orders", "billing"))
                .optional(List.of("search")).apiVersion("2.0.0-SNAPSHOT: x=y #zé\\")
                .source("x/order-history.properties").build();
        StringWriter out = new StringWriter();
        Descriptors.write(module, out);
        String text = out.toString();
        assertTrue(text.chars().allMatch(c -> c < 0x7f), text);
        assertTrue(text.startsWith("format-version=1\nid=order-history\n"), text);
        assertTrue(!text.contains("#") || text.contains("\\#"), text);
        assertEquals(module, Descriptors.parseModule("x/order-history.properties", stream(text)));
    }

    @Test
    void round_trip_of_an_api_descriptor_and_a_bare_module() throws IOException {
        ApiDescriptor api = ApiDescriptor.builder("orders").apiPackages(List.of("com.acme.orders"))
                .provides(List.of("com.acme.orders.OrderApi")).apiVersion("1.0").build();
        StringWriter out = new StringWriter();
        Descriptors.write(api, out);
        assertEquals(api, Descriptors.parseApi("orders.properties", stream(out.toString())));
        ModuleDescriptor bare = ModuleDescriptor.builder("a").apiPackages(List.of("a")).apiVersion("0").build();
        StringWriter bareOut = new StringWriter();
        Descriptors.write(bare, bareOut);
        assertTrue(bareOut.toString().contains("requires=\n"));
        assertEquals(bare, Descriptors.parseModule("a.properties", stream(bareOut.toString())));
        StringWriter again = new StringWriter();
        Descriptors.write(bare, again);
        assertEquals(bareOut.toString(), again.toString());
    }

    @Test
    void rejects_bytes_that_are_not_utf8() {
        TopolithException failure = assertThrows(TopolithException.class, () -> Descriptors.parseModule(SOURCE,
                new ByteArrayInputStream(new byte[] {'i', 'd', '=', (byte) 0xC3, (byte) 0x28})));
        assertEquals(TopolithCode.TL0013, failure.code());
        assertTrue(failure.getMessage().contains("UTF-8"), failure.getMessage());
    }

    @Test
    void accepts_non_ascii_written_as_unicode_escapes() {
        assertEquals("1.0-café", parse(VALID.replace("1.2.0", "1.0-caf\\u00e9")).apiVersion());
    }

    @Test
    void rejects_a_duplicate_key() {
        assertFails(VALID + "api-version=2.0.0\n", "'api-version'", "more than once");
    }

    @Test
    void rejects_a_duplicate_key_spelled_with_an_escape_or_a_continuation() {
        assertFails(VALID + "i\\d=orders\n", "'id'", "more than once");
        assertFails(VALID + "api-\\\n  version=2.0\n", "'api-version'", "more than once");
    }

    @Test
    void rejects_an_unknown_key() {
        assertFails(VALID + "require=payment\n", "'require'", "unknown");
    }

    @Test
    void rejects_requires_and_optional_in_an_api_descriptor() {
        String text = "format-version=1\nid=orders\napi-packages=com.acme.orders\napi-version=1\n";
        for (String key : List.of("requires", "optional")) {
            TopolithException failure = assertThrows(TopolithException.class,
                    () -> Descriptors.parseApi(SOURCE, stream(text + key + "=a\n")));
            assertEquals(TopolithCode.TL0013, failure.code());
            assertTrue(failure.getMessage().contains("'" + key + "'"), failure.getMessage());
        }
        Descriptors.parseApi(SOURCE, stream(text));
    }

    @Test
    void rejects_each_missing_required_key() {
        assertFails(VALID.replace("format-version=1\n", ""), "'format-version'", "missing");
        assertFails(VALID.replace("id=orders\n", ""), "'id'", "missing");
        assertFails(VALID.replace("api-packages=com.acme.orders\n", ""), "'api-packages'", "missing or empty");
        assertFails(VALID.replace("api-version=1.2.0\n", ""), "'api-version'", "missing");
    }

    @Test
    void rejects_an_empty_api_packages_list() {
        assertFails(VALID.replace("api-packages=com.acme.orders", "api-packages= , "), "'api-packages'");
    }

    @Test
    void rejects_a_newer_format_version_with_an_upgrade_message() {
        assertFails(VALID.replace("format-version=1", "format-version=2"),
                "descriptor format 2 is newer than this runtime (max 1)", "Upgrade Topolith");
        assertFails(VALID.replace("format-version=1", "format-version=one"), "'format-version'", "not a number");
        assertFails(VALID.replace("format-version=1", "format-version=0"), "'format-version'");
    }

    private static void assertBadId(String id) {
        TopolithException failure = assertThrows(TopolithException.class,
                () -> ModuleDescriptor.builder(id).apiPackages(List.of("a")).apiVersion("1").build(),
                "id '" + id + "'");
        assertEquals(TopolithCode.TL0013, failure.code(), "id '" + id + "'");
        assertTrue(failure.getMessage().contains("'id'"), failure.getMessage());
    }

    @Test
    void rejects_an_id_with_bad_syntax_or_length() {
        for (String id : List.of("Orders", "1orders", "or_ders", "orders-", "or--ders", "")) {
            assertBadId(id);
        }
        assertBadId("a".repeat(64));
        ModuleDescriptor.builder("a".repeat(63)).apiPackages(List.of("a")).apiVersion("1").build();
        ModuleDescriptor.builder("order-history2").apiPackages(List.of("a")).apiVersion("1").build();
    }

    @Test
    void rejects_an_id_that_differs_from_the_file_name() {
        assertFails(VALID.replace("id=orders", "id=billing"), "'billing'", "file name 'orders.properties'");
    }

    @Test
    void skips_a_leading_byte_order_mark() {
        assertEquals("orders", parse("\uFEFF" + VALID).id());
        assertEquals("orders", Descriptors.parseModule(SOURCE,
                new ByteArrayInputStream(("\uFEFF" + VALID).getBytes(StandardCharsets.UTF_8))).id());
        assertFails("", "'format-version'", "missing");
    }

    @Test
    void takes_the_file_name_from_a_windows_path() {
        String windows = "C:\\app\\META-INF\\topolith\\modules\\orders.properties";
        assertEquals("orders", Descriptors.parseModule(windows, stream(VALID)).id());
        TopolithException failure = assertThrows(TopolithException.class,
                () -> Descriptors.parseModule("C:\\app\\billing.properties", stream(VALID)));
        assertTrue(failure.getMessage().contains("file name 'billing.properties'"), failure.getMessage());
    }

    @Test
    void a_null_list_entry_is_tl0013() {
        for (String key : List.of("api-packages", "provides", "requires", "optional")) {
            ModuleDescriptor.Builder builder = ModuleDescriptor.builder("a").apiPackages(List.of("p")).apiVersion("1");
            List<String> entries = java.util.Arrays.asList("p.A", null);
            switch (key) {
                case "api-packages" -> builder.apiPackages(entries);
                case "provides" -> builder.provides(entries);
                case "requires" -> builder.requires(entries);
                default -> builder.optional(entries);
            }
            TopolithException failure = assertThrows(TopolithException.class, builder::build, key);
            assertEquals(TopolithCode.TL0013, failure.code(), key);
            assertTrue(failure.getMessage().contains("'" + key + "' has a null entry"), failure.getMessage());
        }
    }

    @Test
    void rejects_a_duplicate_list_entry() {
        assertFails(VALID.replace("requires=billing", "requires=billing, billing"), "'requires'", "listed twice");
    }

    @Test
    void rejects_a_bad_id_in_requires_or_optional() {
        assertFails(VALID.replace("requires=billing", "requires=Billing"), "'requires'", "'Billing'");
        assertFails(VALID.replace("optional=search", "optional=a b"), "'optional'", "'a b'");
    }

    @Test
    void rejects_bad_package_and_class_names() {
        assertFails(VALID.replace("api-packages=com.acme.orders", "api-packages=com..orders"), "'api-packages'");
        assertFails(VALID.replace("api-packages=com.acme.orders", "api-packages=com.1orders"), "'api-packages'");
        assertFails(VALID.replace("provides=com.acme.orders.OrderApi", "provides=com.acme.orders.Order-Api"),
                "'provides'");
        assertFails(VALID.replace("provides=com.acme.orders.OrderApi", "provides=OrderApi"), "'provides'");
    }

    @Test
    void rejects_an_api_version_without_a_numeric_major() {
        assertFails(VALID.replace("1.2.0", "v1.2.0"), "'api-version'", "numeric major");
        assertFails(VALID.replace("1.2.0", "1x"), "'api-version'");
        assertEquals("3-beta", parse(VALID.replace("1.2.0", "3-beta")).apiVersion());
        assertEquals("3", parse(VALID.replace("1.2.0", "3")).apiVersion());
    }

    @Test
    void rejects_a_provided_class_outside_the_exact_api_packages() {
        assertFails(VALID.replace("provides=com.acme.orders.OrderApi", "provides=com.acme.other.OrderApi"),
                "'provides'", "not in a listed API package");
        assertFails(VALID.replace("provides=com.acme.orders.OrderApi", "provides=com.acme.orders.sub.OrderApi"),
                "'provides'", "not in a listed API package");
    }

    @Test
    void rejects_a_module_requiring_or_optionally_using_itself() {
        assertFails(VALID.replace("requires=billing", "requires=orders"), "lists itself");
        assertFails(VALID.replace("optional=search", "optional=orders"), "lists itself");
    }

    @Test
    void rejects_one_id_in_both_requires_and_optional() {
        assertFails(VALID.replace("optional=search", "optional=billing"), "'billing'", "both requires and optional");
    }

    @Test
    void reports_every_problem_of_a_file_at_once() {
        TopolithException failure = failure("id=orders\nbogus=1\napi-version=x\n");
        assertTrue(failure.diagnostics().size() >= 4, failure.getMessage());
    }

    @Test
    void parse_modules_collects_every_failure_across_all_inputs() {
        Map<String, InputStream> inputs = new LinkedHashMap<>();
        inputs.put("jar:/b.jar!/b.properties", stream("id=b\n"));
        inputs.put("jar:/a.jar!/a.properties", stream("id=a\nformat-version=9\n"));
        inputs.put("jar:/ok.jar!/ok.properties",
                stream("format-version=1\nid=ok\napi-packages=p\napi-version=1\n"));
        TopolithException failure = assertThrows(TopolithException.class, () -> Descriptors.parseModules(inputs));
        String message = failure.getMessage();
        assertTrue(message.contains("a.jar") && message.contains("b.jar") && !message.contains("ok.jar"), message);
        assertTrue(message.indexOf("a.jar") < message.indexOf("b.jar"), message);
    }

    @Test
    void parse_modules_and_apis_return_descriptors_ordered_by_source() {
        Map<String, InputStream> modules = new LinkedHashMap<>();
        modules.put("z/b.properties", stream("format-version=1\nid=b\napi-packages=p\napi-version=1\n"));
        modules.put("z/a.properties", stream("format-version=1\nid=a\napi-packages=p\napi-version=1\n"));
        assertEquals(List.of("a", "b"), Descriptors.parseModules(modules).stream().map(ModuleDescriptor::id).toList());
        Map<String, InputStream> apis = new LinkedHashMap<>();
        apis.put("z/b.properties", stream("format-version=1\nid=b\napi-packages=p\napi-version=1\n"));
        apis.put("z/a.properties", stream("format-version=1\nid=a\napi-packages=p\napi-version=1\n"));
        assertEquals(List.of("a", "b"), Descriptors.parseApis(apis).stream().map(ApiDescriptor::id).toList());
        apis.put("y/c.properties", stream("id=c\n"));
        assertThrows(TopolithException.class, () -> Descriptors.parseApis(apis));
    }

    @Test
    void the_builder_throws_tl0013_on_a_violation() {
        TopolithException failure = assertThrows(TopolithException.class,
                () -> ModuleDescriptor.builder("a").apiPackages(List.of("p")).apiVersion("1").requires(List.of("a"))
                        .build());
        assertEquals(TopolithCode.TL0013, failure.code());
    }
}
