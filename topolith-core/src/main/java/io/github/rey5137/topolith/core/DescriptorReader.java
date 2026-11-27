package io.github.rey5137.topolith.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** Reads one descriptor file into {@link DescriptorData}, reporting every key-level TL0013 (D-27). */
final class DescriptorReader {
    private static final Set<String> API_KEYS =
            Set.of("format-version", "id", "api-packages", "api-version", "provides", "idempotent");
    private static final Set<String> MODULE_KEYS =
            Set.of("format-version", "id", "package", "api-packages", "api-version", "provides", "idempotent",
                    "requires", "optional");

    /** A byte-order mark is valid UTF-8 and is skipped, not read as part of the first key. */
    private static final int BYTE_ORDER_MARK = 0xFEFF;

    private DescriptorReader() {
    }

    /** A {@link Properties} that remembers keys defined twice instead of keeping the last value silently. */
    private static final class StrictProperties extends Properties {
        private static final long serialVersionUID = 1L;
        final transient Set<String> duplicates = new TreeSet<>();

        @Override
        public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) {
                duplicates.add((String) key);
                return value;
            }
            return super.put(key, value);
        }
    }

    /** Returns the data and appends every problem to {@code problems}; returns null when the file is unreadable. */
    static DescriptorData read(String source, InputStream in, boolean module, List<Diagnostic> problems) {
        StrictProperties properties = new StrictProperties();
        try (PushbackReader reader = new PushbackReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            int first = reader.read();
            if (first != BYTE_ORDER_MARK && first != -1) {
                reader.unread(first);
            }
            properties.load(reader);
        } catch (CharacterCodingException e) {
            problems.add(new Diagnostic(TopolithCode.TL0013, source + ": the file is not valid UTF-8.",
                    "Save the descriptor as UTF-8, or write non-ASCII characters as \\uXXXX escapes."));
            return null;
        } catch (IOException | IllegalArgumentException e) {
            problems.add(new Diagnostic(TopolithCode.TL0013, source + ": the file cannot be read (" + e.getMessage()
                    + ").", "Check that it is a valid .properties file."));
            return null;
        }
        for (String key : properties.duplicates) {
            problems.add(DescriptorData.bad(source, key, "is defined more than once", "Keep one definition."));
        }
        Set<String> known = module ? MODULE_KEYS : API_KEYS;
        for (String key : new TreeSet<>(properties.stringPropertyNames())) {
            if (!known.contains(key)) {
                problems.add(DescriptorData.bad(source, key,
                        "is unknown; it is written by a newer Topolith or misspelled",
                        "Remove or correct the key."));
            }
        }
        DescriptorData data = new DescriptorData(module, properties.getProperty("id"));
        data.source = source;
        String format = properties.getProperty("format-version");
        if (format == null) {
            problems.add(DescriptorData.bad(source, "format-version", "is missing",
                    "Add format-version=" + DescriptorData.FORMAT_VERSION + "."));
        } else {
            try {
                data.formatVersion = Integer.parseInt(format);
            } catch (NumberFormatException e) {
                problems.add(DescriptorData.bad(source, "format-version", "'" + format + "' is not a number",
                        "Set format-version=" + DescriptorData.FORMAT_VERSION + "."));
            }
        }
        data.apiVersion = properties.getProperty("api-version");
        data.apiPackages.addAll(list(properties.getProperty("api-packages")));
        data.provides.addAll(list(properties.getProperty("provides")));
        data.idempotent.addAll(list(properties.getProperty("idempotent")));
        if (module) {
            data.packageName = properties.getProperty("package");
            data.requires.addAll(list(properties.getProperty("requires")));
            data.optional.addAll(list(properties.getProperty("optional")));
        }
        problems.addAll(data.validate());
        return data;
    }

    /** Splits on commas, trims each entry and drops blank ones; a missing key is an empty list. */
    private static List<String> list(String value) {
        List<String> entries = new ArrayList<>();
        if (value != null) {
            for (String entry : value.split(",")) {
                if (!entry.isBlank()) {
                    entries.add(entry.trim());
                }
            }
        }
        return entries;
    }
}
