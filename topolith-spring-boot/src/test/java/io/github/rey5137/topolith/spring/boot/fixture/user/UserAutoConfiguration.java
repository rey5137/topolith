package io.github.rey5137.topolith.spring.boot.fixture.user;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Owned by the user module by package; the module's artifact lists it in AutoConfiguration.imports. */
@AutoConfiguration
public class UserAutoConfiguration {
    @Bean
    String userAutoConfigurationMarker() {
        return "user-auto";
    }
}
