package io.github.rey5137.topolith.spring.boot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/**
 * Maps {@code topolith.modules.<id>.remote.*} to Boot's {@code spring.http.serviceclient.<id>.*} (D-40) as a plain,
 * lowest-priority property source computed once, so a Boot-only value still wins and nothing re-reads the environment.
 */
final class ExternalClientProperties {
    static final String SOURCE_NAME = "topolithExternalClients";
    /** A service id maps to this base URL, which Spring Cloud LoadBalancer resolves (AC-EXT-05, D-42). */
    static final String LB_SCHEME = "lb://";
    static final String CONNECT_TIMEOUT_DEFAULT = "2s";
    static final String READ_TIMEOUT_DEFAULT = "5s";

    private ExternalClientProperties() {
    }

    /** The caller's base URL for module {@code id}: the provider's URL and the fixed {@code /<id>} prefix (D-45). */
    static String baseUrl(String url, String id) {
        return url.trim().replaceAll("/+$", "") + "/" + id;
    }

    /** The load-balanced URL of a service id, without the module prefix. */
    static String serviceUrl(String serviceId) {
        return LB_SCHEME + serviceId.trim();
    }

    static void register(Environment environment, Topology topology) {
        if (environment instanceof ConfigurableEnvironment configurable
                && !configurable.getPropertySources().contains(SOURCE_NAME)) {
            configurable.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME,
                    Collections.unmodifiableMap(properties(environment, topology))));
        }
    }

    /** The mapped keys for every external module; M2.6 (D-12) recomputes it on refresh. */
    static Map<String, Object> properties(Environment environment, Topology topology) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String id : topology.idsOf(Mode.EXTERNAL)) {
            String target = "spring.http.serviceclient." + id + ".";
            String source = TopologyResolver.MODES + "." + id + ".remote.";
            String url = environment.getProperty(source + "url");
            if (url != null && !url.isBlank()) {
                values.put(target + "base-url", baseUrl(url, id));
            }
            String service = environment.getProperty(source + "service-id");
            if ((url == null || url.isBlank()) && service != null && !service.isBlank()) {
                values.put(target + "base-url", serviceUrl(service) + "/" + id);
            }
            timeout(environment, values, target, source, "connect-timeout", CONNECT_TIMEOUT_DEFAULT);
            timeout(environment, values, target, source, "read-timeout", READ_TIMEOUT_DEFAULT);
        }
        return values;
    }

    /** The Topolith value, else the default, unless Boot's group or global key is set: then Boot's value stands. */
    private static void timeout(Environment environment, Map<String, Object> values, String target, String source,
            String key, String fallback) {
        String own = environment.getProperty(source + key);
        if (own != null) {
            values.put(target + key, own);
        } else if (!environment.containsProperty(target + key)
                && !environment.containsProperty("spring.http.clients." + key)) {
            values.put(target + key, fallback);
        }
    }
}
