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
        private final Set<String> byValue = new LinkedHashSet<>();
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
            byValue.remove(id);
            values.keySet().removeIf(key -> key.startsWith(ModeProperties.key(id, "")));
            values.putAll(ModeProperties.of(id, value, url));
            return this;
        }

        /** Selects internal mode. */
        public Builder internal() { return mode("internal", null); }

        /**
         * Puts the by-value proxy (R-TEST-04) in front of the module's contracts; call it after {@code internal()}.
         * It takes effect through {@link #applyTo(ApplicationContextRunner)}; a Spring test selects it with
         * {@link TopolithByValue}.
         *
         * @throws IllegalStateException when no mode was selected for the module yet
         */
        public Builder byValue() {
            if (id == null || unset.contains(id)) {
                throw new IllegalStateException("byValue() needs a mode first; call internal() after module("
                        + (id == null ? "..." : "\"" + id + "\"") + ") and then byValue()");
            }
            byValue.add(id);
            return this;
        }

        /** Selects none mode. */
        public Builder none() { return mode("none", null); }

        /**
         * Selects loopback mode (R-MODE-06): the module runs in the test's context and its callers reach it over HTTP,
         * so the context needs a listening server ({@code webEnvironment = RANDOM_PORT}). It clears the host's
         * {@code remote.url} and {@code remote.service-id}.
         */
        public Builder loopback() { return mode("loopback", null); }

        /** Selects external mode at a base URL. */
        public Builder external(URI uri) { return mode("external", uri.toString()); }

        /** Selects external mode at a mock server. */
        public Builder external(TopolithMockServer server) { return external(server.url()); }

        private void requireModes() {
            if (!unset.isEmpty()) {
                throw new IllegalStateException("No mode selected for module(s) " + unset
                        + "; call internal(), external(...), loopback() or none() after module(id)");
            }
        }

        /**
         * Returns a runner with these properties.
         *
         * @throws IllegalStateException when a module was named without a mode
         */
        public ApplicationContextRunner applyTo(ApplicationContextRunner runner) {
            requireModes();
            ApplicationContextRunner configured = runner.withPropertyValues(values.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new));
            Set<String> ids = Set.copyOf(byValue);
            return ids.isEmpty() ? configured : configured.withInitializer(context -> ByValue.install(context, ids));
        }

        /**
         * Adds these properties to a dynamic registry.
         *
         * @throws IllegalStateException when a module was named without a mode, or {@code byValue()} was called: a
         *     property registry cannot install the proxy, so use {@link TopolithByValue}
         */
        public void applyTo(DynamicPropertyRegistry registry) {
            requireModes();
            if (!byValue.isEmpty()) {
                throw new IllegalStateException("byValue() for module(s) " + byValue
                        + " cannot be applied to a property registry; use @TopolithByValue on the test class");
            }
            values.forEach((key, value) -> registry.add(key, () -> value));
        }
    }
}
