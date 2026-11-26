package io.github.rey5137.topolith.spring.test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import io.github.rey5137.topolith.core.Incubating;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Explicit test modes; modules not named here remain unset. */
@Incubating
public final class TopolithTestModes {
    private TopolithTestModes() { }

    /** Starts configuring a module. */
    public static Builder module(String id) { return new Builder().module(id); }

    /** A chain of explicit mode selections. */
    @Incubating
    public static final class Builder {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Set<String> unset = new LinkedHashSet<>();
        private String id;

        private Builder() { }

        /** Selects a module to configure. */
        public Builder module(String moduleId) {
            id = moduleId;
            String prefix = ModeProperties.key(moduleId, "");
            if (values.keySet().stream().noneMatch(key -> key.startsWith(prefix))) { unset.add(moduleId); }
            return this;
        }

        private Builder mode(String value, String url) {
            unset.remove(id);
            values.keySet().removeIf(key -> key.startsWith(ModeProperties.key(id, "")));
            values.putAll(ModeProperties.of(id, value, url));
            return this;
        }

        /** Selects internal mode. */
        public Builder internal() { return mode("internal", null); }

        /** Selects none mode. */
        public Builder none() { return mode("none", null); }

        /** Selects external mode at a base URL. */
        public Builder external(URI uri) { return mode("external", uri.toString()); }

        /** Selects external mode at a mock server. */
        public Builder external(TopolithMockServer server) { return external(server.url()); }

        private void requireModes() {
            if (!unset.isEmpty()) {
                throw new IllegalStateException("No mode selected for module(s) " + unset
                        + "; call internal(), external(...) or none() after module(id)");
            }
        }

        /**
         * Returns a runner with these properties.
         *
         * @throws IllegalStateException when a module was named without a mode
         */
        public ApplicationContextRunner applyTo(ApplicationContextRunner runner) {
            requireModes();
            return runner.withPropertyValues(values.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new));
        }

        /**
         * Adds these properties to a dynamic registry.
         *
         * @throws IllegalStateException when a module was named without a mode
         */
        public void applyTo(DynamicPropertyRegistry registry) {
            requireModes();
            values.forEach((key, value) -> registry.add(key, () -> value));
        }
    }
}
