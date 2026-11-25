package io.github.rey5137.topolith.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopolithCodeCatalogTest {
    private static final Pattern ROW = Pattern.compile("^\\| (TL\\d{4}) \\|.*\\| (failure|warning) \\|[^|]*\\|$");

    // INV-10: the enum is the catalog table, code for code and severity for severity.
    @Test
    void enum_matches_the_catalog_table() throws IOException {
        Path catalog = Path.of(System.getProperty("user.dir")).resolve("../docs/spec/reference/90-catalog.md");
        Map<String, String> table = new LinkedHashMap<>();
        for (String line : Files.readAllLines(catalog, StandardCharsets.UTF_8)) {
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                table.put(row.group(1), row.group(2));
            }
        }
        Map<String, String> enumerated = new LinkedHashMap<>();
        for (TopolithCode code : TopolithCode.values()) {
            enumerated.put(code.id(), code.defaultSeverity().name().toLowerCase(java.util.Locale.ROOT));
        }
        assertEquals(29, table.size());
        assertEquals(table, enumerated);
    }
}
