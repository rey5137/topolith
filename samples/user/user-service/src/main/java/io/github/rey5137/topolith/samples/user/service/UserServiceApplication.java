package io.github.rey5137.topolith.samples.user.service;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Standalone user service: user defaults to internal, payment stays external. */
@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(UserServiceApplication.class)
                .properties("spring.config.location=classpath:/config/user-service/")
                .run(args);
    }
}
