package io.github.rey5137.topolith.spring.boot.fixture.outside;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Outside every module package; the user module's artifact lists it in AutoConfiguration.imports. */
@AutoConfiguration
public class ColocatedAutoConfiguration {
    @Bean
    String colocatedAutoConfigurationMarker() {
        return "colocated";
    }
}
