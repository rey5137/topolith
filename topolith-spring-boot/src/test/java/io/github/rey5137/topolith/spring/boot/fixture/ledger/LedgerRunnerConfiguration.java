package io.github.rey5137.topolith.spring.boot.fixture.ledger;

import javax.sql.DataSource;

import liquibase.integration.spring.SpringLiquibase;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A runner the ledger module declares on the host's DataSource, chosen by {@code ledger.runner}: it owns the module's
 * migrations, so Topolith adds none (D-112).
 */
@Configuration(proxyBeanMethods = false)
public class LedgerRunnerConfiguration {
    public static final String HISTORY = "ledger_own_history";
    public static final String CHANGELOG = "ledger_own_changelog";

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "ledger.runner", havingValue = "flyway")
    public FlywayMigrationInitializer ledgerFlywayInitializer(DataSource dataSource) {
        return new FlywayMigrationInitializer(Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/modules/ledger").table(HISTORY).baselineOnMigrate(true).baselineVersion("0")
                .load());
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "ledger.runner", havingValue = "liquibase")
    public SpringLiquibase ledgerLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/modules/ledger/db.changelog-master.yaml");
        liquibase.setDatabaseChangeLogTable(CHANGELOG);
        liquibase.setDatabaseChangeLogLockTable(CHANGELOG + "_lock");
        return liquibase;
    }
}
