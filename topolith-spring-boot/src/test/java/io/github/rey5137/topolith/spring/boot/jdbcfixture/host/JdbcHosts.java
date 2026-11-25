package io.github.rey5137.topolith.spring.boot.jdbcfixture.host;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

public final class JdbcHosts {
    private JdbcHosts() {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class Host {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableJdbcRepositories(basePackages = "io.github.rey5137.topolith.spring.boot.jdbcfixture.jdbcitem")
    public static class LeakingHost {
    }
}
