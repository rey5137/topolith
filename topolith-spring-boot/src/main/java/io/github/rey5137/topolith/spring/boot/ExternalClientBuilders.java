package io.github.rey5137.topolith.spring.boot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.client.RestClient;

/**
 * The {@link RestClient.Builder} of each external module's service group, as the group's configurers left it: base
 * URL, mandatory timeouts and any load balancing. The readiness version check builds its request from it (R-VAL-04).
 */
final class ExternalClientBuilders {
    private final Map<String, RestClient.Builder> builders = new ConcurrentHashMap<>();

    void put(String module, RestClient.Builder builder) {
        builders.put(module, builder);
    }

    RestClient.Builder get(String module) {
        return builders.get(module);
    }
}
