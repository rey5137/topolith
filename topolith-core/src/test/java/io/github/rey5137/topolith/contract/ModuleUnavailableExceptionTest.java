package io.github.rey5137.topolith.contract;

import java.io.IOException;
import java.lang.reflect.Modifier;

import io.github.rey5137.topolith.core.TopolithException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R-CAT-02 and D-43: the exception callers see when a module cannot be reached. */
class ModuleUnavailableExceptionTest {
    @Test
    void ac_ext_10_carries_the_module_id_message_and_cause() {
        IOException cause = new IOException("refused");
        ModuleUnavailableException exception = new ModuleUnavailableException("payment", "down", cause);
        assertEquals("payment", exception.moduleId());
        assertEquals("down", exception.getMessage());
        assertSame(cause, exception.getCause());
        ModuleUnavailableException plain = new ModuleUnavailableException("payment", "down");
        assertNull(plain.getCause());
        assertThrows(NullPointerException.class, () -> new ModuleUnavailableException(null, "down"));
    }

    @Test
    void ac_ext_10_is_unchecked_final_and_not_a_topology_failure() {
        assertTrue(RuntimeException.class.isAssignableFrom(ModuleUnavailableException.class));
        assertFalse(TopolithException.class.isAssignableFrom(ModuleUnavailableException.class));
        assertTrue(Modifier.isFinal(ModuleUnavailableException.class.getModifiers()));
    }
}
