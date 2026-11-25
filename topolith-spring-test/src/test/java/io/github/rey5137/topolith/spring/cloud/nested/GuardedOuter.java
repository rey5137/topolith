package io.github.rey5137.topolith.spring.cloud.nested;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Configuration;

@ConditionalOnClass(DiscoveryClient.class)
public class GuardedOuter {
    @Configuration
    public static class NestedConfiguration {
        DiscoveryClient client;
    }
}
