package io.github.rey5137.topolith.spring.boot.errorfixture.unmapped;

import io.github.rey5137.topolith.spring.boot.errorfixture.unmapped.api.UnmappedContract;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class UnmappedConfiguration {
    @Bean
    UnmappedContract unmappedContract() {
        return () -> "unmapped";
    }
}
