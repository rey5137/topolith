package io.github.rey5137.topolith.spring.cloud.composed;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.springframework.context.annotation.Configuration;

@Retention(RetentionPolicy.RUNTIME)
@Configuration
@interface IntegrationConfiguration {
}

@IntegrationConfiguration
public class ComposedConfiguration {
    org.springframework.cloud.client.discovery.DiscoveryClient client;
}
