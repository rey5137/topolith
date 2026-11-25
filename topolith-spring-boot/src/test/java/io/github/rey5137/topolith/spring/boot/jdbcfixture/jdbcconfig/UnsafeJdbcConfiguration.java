package io.github.rey5137.topolith.spring.boot.jdbcfixture.jdbcconfig;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

@Configuration(proxyBeanMethods = false)
public class UnsafeJdbcConfiguration extends AbstractJdbcConfiguration {
}
