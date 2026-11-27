package io.github.rey5137.topolith.samples.hosts.monolith;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The sample's resource-server setup: a bearer token on any request authenticates it, and every route stays open.
 * The token is checked against the HMAC key in {@code sample.jwt.secret}, which the sample's tests generate for each
 * run, so the sample needs no identity provider; without it a random key is used and no token is accepted. A real
 * service points {@code spring.security.oauth2.resourceserver.jwt} at its identity provider instead.
 */
@Configuration(proxyBeanMethods = false)
class SampleSecurity {
    @Bean
    SecurityFilterChain sampleFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    JwtDecoder sampleJwtDecoder(@Value("${sample.jwt.secret:}") String secret) {
        byte[] key = secret.isEmpty() ? new byte[32] : secret.getBytes(StandardCharsets.UTF_8);
        if (secret.isEmpty()) {
            new SecureRandom().nextBytes(key);
        }
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).build();
    }
}
