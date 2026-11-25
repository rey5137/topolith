package io.github.rey5137.topolith.spring.cloud.good;

@org.springframework.boot.autoconfigure.AutoConfiguration
@org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
        org.springframework.cloud.client.discovery.DiscoveryClient.class)
public class GoodConfiguration {
    org.springframework.cloud.client.discovery.DiscoveryClient client;
}
