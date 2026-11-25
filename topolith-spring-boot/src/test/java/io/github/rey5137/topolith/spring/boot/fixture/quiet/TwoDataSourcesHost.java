package io.github.rey5137.topolith.spring.boot.fixture.quiet;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class TwoDataSourcesHost {
    @Bean
    DataSource firstDataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:first");
    }

    @Bean
    DataSource secondDataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:second");
    }
}
