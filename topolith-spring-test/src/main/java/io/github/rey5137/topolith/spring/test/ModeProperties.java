package io.github.rey5137.topolith.spring.test;

import java.util.LinkedHashMap;
import java.util.Map;

/** The property keys a test mode selection writes, shared by the annotation and the builder. */
final class ModeProperties {
    private ModeProperties() { }

    static String key(String id, String suffix) { return "topolith.modules." + id + "." + suffix; }

    /**
     * A module's mode, and for external the URL. A blank {@code remote.service-id} counts as unset (TL0003), so an
     * external selection clears a host's service id and its URL wins.
     */
    static Map<String, String> of(String id, String mode, String url) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key(id, "mode"), mode);
        if (url != null) {
            values.put(key(id, "remote.url"), url);
            values.put(key(id, "remote.service-id"), "");
        }
        return values;
    }
}
