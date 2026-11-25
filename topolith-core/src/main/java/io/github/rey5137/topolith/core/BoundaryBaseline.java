package io.github.rey5137.topolith.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The packaged baseline of frozen boundary violations of one module, {@code META-INF/topolith/baseline/<id>.txt}
 * (D-25, D-57). One line per violation, {@code <referencing class> -> <referenced class>}, with binary class names;
 * blank lines and lines starting with {@code #} are ignored. The M3.2 tooling writes it and the startup boundary
 * check reads it.
 */
@Incubating
public final class BoundaryBaseline {
    /** The resource directory of the baseline files. */
    public static final String DIRECTORY = "META-INF/topolith/baseline/";
    private static final String SEPARATOR = " -> ";

    private BoundaryBaseline() {
    }

    /** One frozen violation: {@code referencing} depends on {@code referenced}. */
    public record Violation(String referencing, String referenced) implements Comparable<Violation> {
        @Override
        public int compareTo(Violation other) {
            int byFrom = referencing.compareTo(other.referencing);
            return byFrom != 0 ? byFrom : referenced.compareTo(other.referenced);
        }

        @Override
        public String toString() {
            return referencing + SEPARATOR + referenced;
        }
    }

    /** The resource path of the baseline of module {@code id}. */
    public static String resource(String id) {
        return DIRECTORY + id + ".txt";
    }

    /** Renders the violations sorted and without duplicates, one per line, under a {@code #} header. */
    public static String format(Collection<Violation> violations) {
        StringBuilder text = new StringBuilder("# Frozen boundary violations: <referencing class> -> <referenced "
                + "class>\n");
        new TreeSet<>(violations).forEach(violation -> text.append(violation).append('\n'));
        return text.toString();
    }

    /** Parses baseline text; throws {@link IllegalArgumentException} naming the first malformed line. */
    public static Set<Violation> parse(String text) {
        Set<Violation> violations = new LinkedHashSet<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int at = line.indexOf(SEPARATOR);
            if (at <= 0 || at + SEPARATOR.length() >= line.length() || line.indexOf(SEPARATOR, at + 1) >= 0) {
                throw new IllegalArgumentException("Malformed baseline line '" + line + "': expected "
                        + "'<referencing class> -> <referenced class>'");
            }
            violations.add(new Violation(line.substring(0, at).strip(),
                    line.substring(at + SEPARATOR.length()).strip()));
        }
        return violations;
    }

    /** Parses a baseline resource as UTF-8 and closes the stream. */
    public static Set<Violation> parse(InputStream in) {
        try (in; BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return parse(String.join("\n", reader.lines().toList()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
