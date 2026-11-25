package io.github.rey5137.topolith.spring.boot.fixture.inventory;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Pattern A Flyway variants, chosen by {@code inventory.flyway}: a bare Flyway migrates nothing; the others do. */
@Configuration(proxyBeanMethods = false)
public class InventoryFlywayConfiguration {

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "inventory.flyway", havingValue = "bare")
    public Flyway inventoryBareFlyway(@Qualifier("inventory") DataSource dataSource) {
        return flyway(dataSource);
    }

    @Bean(defaultCandidate = false)
    @Qualifier("inventory")
    @ConditionalOnProperty(name = "inventory.flyway", havingValue = "initializer")
    public Flyway inventoryFlyway(@Qualifier("inventory") DataSource dataSource) {
        return flyway(dataSource);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "inventory.flyway", havingValue = "initializer")
    public FlywayMigrationInitializer inventoryFlywayInitializer(@Qualifier("inventory") Flyway flyway) {
        return new FlywayMigrationInitializer(flyway);
    }

    @Bean(defaultCandidate = false, initMethod = "migrate")
    @ConditionalOnProperty(name = "inventory.flyway", havingValue = "init-method")
    public Flyway inventoryMigratingFlyway(@Qualifier("inventory") DataSource dataSource) {
        return flyway(dataSource);
    }

    private static Flyway flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/modules/inventory")
                .baselineOnMigrate(true).baselineVersion("0").load();
    }
}
