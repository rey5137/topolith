package io.github.rey5137.topolith.spring.test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

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
        private String id;

        private Builder() { }

        /** Selects a module to configure. */
        public Builder module(String moduleId) { id = moduleId; return this; }

        private Builder mode(String value) {
            values.put("topolith.modules." + id + ".mode", value);
            values.remove("topolith.modules." + id + ".remote.url");
            return this;
        }

        /** Selects internal mode. */
        public Builder internal() { return mode("internal"); }

        /** Selects none mode. */
        public Builder none() { return mode("none"); }

        /** Selects external mode at a base URL. */
        public Builder external(URI uri) {
            mode("external");
            values.put("topolith.modules." + id + ".remote.url", uri.toString());
            return this;
        }

        /** Selects external mode at a mock server. */
        public Builder external(TopolithMockServer server) { return external(server.url()); }

        /** Returns a runner with these properties. */
        public ApplicationContextRunner applyTo(ApplicationContextRunner runner) {
            return runner.withPropertyValues(values.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new));
        }

        /** Adds these properties to a dynamic registry. */
        public void applyTo(DynamicPropertyRegistry registry) {
            values.forEach((key, value) -> registry.add(key, () -> value));
        }
    }
}
