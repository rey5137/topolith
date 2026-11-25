package io.github.rey5137.topolith.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses and writes descriptor files (D-27). Discovery is not core's: callers pass {@code (source, stream)} pairs,
 * where the source is a location string such as a JAR URL and path whose last segment is {@code <id>.properties}.
 * Each stream is closed after it is read.
 */
@Incubating
public final class Descriptors {
    private static final String LINE = "\n";

    private Descriptors() {
    }

    /** Parses one module descriptor; throws a TL0013 {@link TopolithException} listing every problem. */
    public static ModuleDescriptor parseModule(String source, InputStream in) {
        List<Diagnostic> problems = new ArrayList<>();
        DescriptorData data = DescriptorReader.read(source, in, true, problems);
        if (!problems.isEmpty()) {
            throw new TopolithException(problems);
        }
        return new ModuleDescriptor(data);
    }

    /** Parses one API descriptor; throws a TL0013 {@link TopolithException} listing every problem. */
    public static ApiDescriptor parseApi(String source, InputStream in) {
        List<Diagnostic> problems = new ArrayList<>();
        DescriptorData data = DescriptorReader.read(source, in, false, problems);
        if (!problems.isEmpty()) {
            throw new TopolithException(problems);
        }
        return new ApiDescriptor(data);
    }

    /**
     * Parses every module descriptor, ordered by source. All TL0013 problems of all inputs are collected into one
     * exception (stage 1).
     */
    public static List<ModuleDescriptor> parseModules(Map<String, InputStream> inputs) {
        List<Diagnostic> problems = new ArrayList<>();
        List<ModuleDescriptor> parsed = new ArrayList<>();
        for (Map.Entry<String, InputStream> input : new TreeMap<>(inputs).entrySet()) {
            DescriptorData data = DescriptorReader.read(input.getKey(), input.getValue(), true, problems);
            if (problems.isEmpty()) {
                parsed.add(new ModuleDescriptor(data));
            }
        }
        if (!problems.isEmpty()) {
            throw new TopolithException(problems);
        }
        return Collections.unmodifiableList(parsed);
    }

    /** Like {@link #parseModules} for API descriptors. */
    public static List<ApiDescriptor> parseApis(Map<String, InputStream> inputs) {
        List<Diagnostic> problems = new ArrayList<>();
        List<ApiDescriptor> parsed = new ArrayList<>();
        for (Map.Entry<String, InputStream> input : new TreeMap<>(inputs).entrySet()) {
            DescriptorData data = DescriptorReader.read(input.getKey(), input.getValue(), false, problems);
            if (problems.isEmpty()) {
                parsed.add(new ApiDescriptor(data));
            }
        }
        if (!problems.isEmpty()) {
            throw new TopolithException(problems);
        }
        return Collections.unmodifiableList(parsed);
    }

    /**
     * Indexes API descriptors by id, ordered by id. Two descriptors with one id fail with TL0006; a module and an API
     * descriptor sharing an id is normal.
     */
    public static Map<String, ApiDescriptor> indexApis(Collection<ApiDescriptor> apis) {
        List<Diagnostic> duplicates = Identity.duplicates(apis, ApiDescriptor::id, ApiDescriptor::source,
                "API descriptor");
        if (!duplicates.isEmpty()) {
            throw new TopolithException(duplicates);
        }
        Map<String, ApiDescriptor> index = new TreeMap<>();
        apis.forEach(api -> index.put(api.id(), api));
        return Collections.unmodifiableMap(new LinkedHashMap<>(index));
    }

    /** Writes the module descriptor as ASCII with every key, in a fixed order and without a timestamp. */
    public static void write(ModuleDescriptor module, Writer out) throws IOException {
        writeCommon(module.api(), out, module.packageName());
        entry(out, "requires", String.join(",", module.requires()));
        entry(out, "optional", String.join(",", module.optional()));
    }

    /** Writes the API descriptor as ASCII with every key, in a fixed order and without a timestamp. */
    public static void write(ApiDescriptor api, Writer out) throws IOException {
        writeCommon(api, out, null);
    }

    private static void writeCommon(ApiDescriptor api, Writer out, String packageName) throws IOException {
        entry(out, "format-version", Integer.toString(api.formatVersion()));
        entry(out, "id", api.id());
        if (packageName != null) {
            entry(out, "package", packageName);
        }
        entry(out, "api-packages", String.join(",", api.apiPackages()));
        entry(out, "api-version", api.apiVersion());
        entry(out, "provides", String.join(",", api.provides()));
    }

    private static void entry(Writer out, String key, String value) throws IOException {
        out.write(key + "=" + escape(value) + LINE);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\', '=', ':', '#', '!' -> escaped.append('\\').append(c);
                case ' ' -> escaped.append("\\ ");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        escaped.append(String.format("\\u%04X", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
