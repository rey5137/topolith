package io.github.rey5137.topolith.spring.a_later;

import io.github.rey5137.topolith.spring.boot.fixture.shared.SharedService;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * A host-owned auto-configuration that backs off when a SharedService exists, as a Boot default would. Its package
 * sorts before {@code ...spring.boot.TopolithAutoConfiguration}, and Boot sorts by name first, so only
 * {@code @AutoConfigureOrder} on Topolith's auto-configuration puts that one ahead of it.
 */
@AutoConfiguration
public class LaterAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SharedService.class)
    SharedService fallbackSharedService() {
        return () -> "fallback";
    }
}
