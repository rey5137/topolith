package io.github.rey5137.topolith.samples.user;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/** The user module's owned auto-configuration; a {@code none} host must remove it. */
@AutoConfiguration
public class UserAutoConfiguration {
    @Bean
    UserMarker userMarker() {
        return new UserMarker();
    }

    /** A marker bean that proves the module's auto-configuration ran. */
    public static class UserMarker {
    }
}
