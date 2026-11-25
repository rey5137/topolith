package io.github.rey5137.topolith.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopolithExceptionTest {
    private static final Diagnostic FIRST = new Diagnostic(TopolithCode.TL0006, "two orders", "remove one");
    private static final Diagnostic SECOND = new Diagnostic(TopolithCode.TL0008, "a cycle", "break it");

    @Test
    void code_is_the_first_diagnostics_code_and_message_has_one_line_each() {
        TopolithException exception = new TopolithException(List.of(FIRST, SECOND));
        assertEquals(TopolithCode.TL0006, exception.code());
        assertEquals(List.of(FIRST, SECOND), exception.diagnostics());
        assertEquals("TL0006: two orders remove one\nTL0008: a cycle break it", exception.getMessage());
    }

    @Test
    void diagnostics_are_unmodifiable() {
        TopolithException exception = new TopolithException(FIRST);
        assertThrows(UnsupportedOperationException.class, () -> exception.diagnostics().add(SECOND));
    }

    @Test
    void survives_java_serialization() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(new TopolithException(List.of(FIRST, SECOND)));
        }
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            TopolithException copy = (TopolithException) in.readObject();
            assertEquals(TopolithCode.TL0006, copy.code());
            assertEquals(List.of(FIRST, SECOND), copy.diagnostics());
        }
    }

    @Test
    void rejects_an_empty_list() {
        assertThrows(IllegalArgumentException.class, () -> new TopolithException(List.of()));
    }

    @Test
    void rejects_a_warning() {
        Diagnostic warning = new Diagnostic(TopolithCode.TL0010, "refreshed", "restart");
        assertThrows(IllegalArgumentException.class, () -> new TopolithException(List.of(FIRST, warning)));
    }

    @Test
    void diagnostics_compare_by_value() {
        assertEquals(FIRST, new Diagnostic(TopolithCode.TL0006, "two orders", "remove one"));
        assertEquals(FIRST.hashCode(), new Diagnostic(TopolithCode.TL0006, "two orders", "remove one").hashCode());
        assertTrue(FIRST.toString().startsWith("TL0006: "));
    }
}
