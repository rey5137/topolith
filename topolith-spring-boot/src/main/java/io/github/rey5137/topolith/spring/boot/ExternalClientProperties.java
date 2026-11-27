package io.github.rey5137.topolith.spring.boot;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceSettings;
import org.springframework.boot.context.properties.bind.Binder;
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
    /** The base URL a loopback group carries until its client targets the started server (AC-MODE-53). */
    static final String PLACEHOLDER_BASE = "http://localhost/";
    static final String CONNECT_TIMEOUT_DEFAULT = "2s";
    static final String READ_TIMEOUT_DEFAULT = "5s";

    private ExternalClientProperties() {
    }

    /** The caller's base URL for module {@code id}: the provider's URL and the fixed {@code /<id>} prefix (D-45). */
    static String baseUrl(String url, String id) {
        return url.trim().replaceAll("/+$", "") + "/" + id;
    }

    /** The key prefix of module {@code id}'s group in Boot's HTTP service-client properties. */
    static String groupPrefix(String id) {
        return "spring.http.serviceclient." + id + ".";
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

    /**
     * The mapped keys for every external and loopback module. A loopback module sets no {@code remote.url} or
     * {@code remote.service-id} (TL0003), so it maps its timeouts and a placeholder base URL (AC-MODE-53).
     */
    static Map<String, Object> properties(Environment environment, Topology topology) {
        Set<String> clients = ExternalHttpServicesRegistrar.clientModules(topology);
        Map<String, Object> values = properties(environment, clients, new HashSet<>(topology.idsOf(Mode.LOOPBACK)));
        // Spring Cloud's global HTTP-service breaker is on unless its key says otherwise, and it would wrap every
        // module group with a time limiter on another thread (R-EXT-15), so an unset key is defaulted to false while
        // a module retries or enables its breaker; an explicit true is TL0003 (D-106)
        if (!environment.containsProperty(ResilienceSettings.GLOBAL_BREAKER) && resilient(environment, clients)) {
            values.put(ResilienceSettings.GLOBAL_BREAKER, "false");
        }
        return values;
    }

    private static boolean resilient(Environment environment, Set<String> ids) {
        Binder binder = Binder.get(environment);
        for (String id : ids) {
            if (ResilienceSettings.retry(binder, id).active() || ResilienceSettings.breaker(binder, id).enabled()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The mapped keys for the given external modules; a refresh recomputes one external module's (D-50). For external
     * ids only: a loopback id passed here would lose its placeholder base URL.
     */
    static Map<String, Object> properties(Environment environment, Collection<String> ids) {
        return properties(environment, ids, Set.of());
    }

    private static Map<String, Object> properties(Environment environment, Collection<String> ids,
            Set<String> loopback) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String id : ids) {
            String target = groupPrefix(id);
            if (loopback.contains(id)) {
                // the loopback URI factory replaces it; a base URL without the lb scheme keeps Spring Cloud
                // LoadBalancer's group configurer off the group (it would treat the host as a service id)
                values.put(target + "base-url", PLACEHOLDER_BASE + id);
            }
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
