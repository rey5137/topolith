package io.github.rey5137.topolith.spring.boot.jdbcfixture.jdbcown;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableJdbcRepositories(basePackageClasses = OwnedItemRepository.class,
        jdbcOperationsRef = "ownedJdbcOperations", transactionManagerRef = "ownedTransactionManager")
public class OwnedJdbcConfiguration {
    @Bean(defaultCandidate = false)
    @Qualifier("owned")
    DataSource ownedDataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:owned;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Bean(defaultCandidate = false)
    @Qualifier("owned")
    NamedParameterJdbcOperations ownedJdbcOperations(@Qualifier("owned") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean(defaultCandidate = false)
    @Qualifier("owned")
    PlatformTransactionManager ownedTransactionManager(@Qualifier("owned") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
