package io.github.rey5137.topolith.spring.boot.fixture.user;

import io.github.rey5137.topolith.spring.boot.fixture.shared.SharedService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class UserConfiguration {
    @Bean
    SharedService userSharedService() {
        return () -> "user";
    }
}
