package io.github.rey5137.topolith.core;

import io.github.rey5137.topolith.core.BoundaryBaseline.Violation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundaryBaselineTest {
    @Test
    void parse_ignores_blank_and_comment_lines() {
        String text = "# header\n\n  \na.B -> c.D$Inner\n# another\na.B -> c.D$Inner\ne.F -> c.G\n";
        assertEquals(Set.of(new Violation("a.B", "c.D$Inner"), new Violation("e.F", "c.G")),
                BoundaryBaseline.parse(text));
        assertEquals(2, BoundaryBaseline.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))
                .size());
    }

    @Test
    void parse_rejects_a_malformed_line() {
        assertThrows(IllegalArgumentException.class, () -> BoundaryBaseline.parse("a.B c.D"));
        assertThrows(IllegalArgumentException.class, () -> BoundaryBaseline.parse("a.B -> c.D -> e.F"));
    }

    @Test
    void format_sorts_dedupes_and_round_trips() {
        List<Violation> violations = List.of(new Violation("z.Z", "a.A"), new Violation("a.A", "b.B"),
                new Violation("a.A", "b.B"));
        String text = BoundaryBaseline.format(violations);
        assertEquals("a.A -> b.B\nz.Z -> a.A\n", text.substring(text.indexOf('\n') + 1));
        assertEquals(Set.copyOf(violations), BoundaryBaseline.parse(text));
    }
}
