package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ClassUtils;
import org.springframework.web.service.annotation.HttpExchange;

/** TL0026 (D-41): an external module needs the HTTP client stack and {@code @HttpExchange} contracts it can load. */
final class ExternalContracts {
    static final String REGISTRAR = "org.springframework.web.service.registry.AbstractHttpServiceRegistrar";
    static final String CLIENT_AUTO_CONFIGURATION =
            "org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration";

    private ExternalContracts() {
    }

    /** Adds a TL0026 diagnostic for every external module that has no usable client. */
    static void check(Set<String> externals, Map<String, Set<String>> contracts, ClassLoader loader,
            List<Diagnostic> problems) {
        if (externals.isEmpty()) {
            return;
        }
        boolean web = ClassUtils.isPresent(REGISTRAR, loader);
        boolean boot = ClassUtils.isPresent(CLIENT_AUTO_CONFIGURATION, loader);
        for (String id : externals) {
            if (!web || !boot) {
                String missing = web ? "Spring Boot's HTTP service-client auto-configuration" : "spring-web";
                problems.add(new Diagnostic(TopolithCode.TL0026, "external module '" + id
                        + "' has no HTTP client: " + missing + " is not on the classpath.",
                        "Add spring-boot-starter-restclient to the host."));
                continue;
            }
            for (String name : contracts.getOrDefault(id, Set.of())) {
                Class<?> type;
                try {
                    type = Class.forName(name, false, loader);
                } catch (ClassNotFoundException | LinkageError e) {
                    problems.add(new Diagnostic(TopolithCode.TL0026, "external module '" + id + "' provides "
                            + name + ", which cannot be loaded.", "Add the module's API JAR to the host classpath."));
                    continue;
                }
                if (!exchange(type)) {
                    problems.add(new Diagnostic(TopolithCode.TL0026, "contract " + name + " of external module '" + id
                            + "' carries no @HttpExchange.", "Annotate the contract or its methods with "
                            + "@HttpExchange (or @GetExchange and the like)."));
                }
            }
        }
    }

    static boolean exchange(Class<?> type) {
        if (MergedAnnotations.from(type).isPresent(HttpExchange.class)) {
            return true;
        }
        for (Method method : type.getMethods()) {
            if (MergedAnnotations.from(method).isPresent(HttpExchange.class)) {
                return true;
            }
        }
        return false;
    }
}
