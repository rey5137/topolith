package io.github.rey5137.topolith.spring.boot;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.rey5137.topolith.core.Topology;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(value = Endpoint.class,
        name = "org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint")
class TopolithEndpointConfiguration {
    @Bean
    @ConditionalOnAvailableEndpoint
    TopolithEndpoint topolithEndpoint(Topology topolithTopology) {
        return new TopolithEndpoint(topolithTopology);
    }
}
