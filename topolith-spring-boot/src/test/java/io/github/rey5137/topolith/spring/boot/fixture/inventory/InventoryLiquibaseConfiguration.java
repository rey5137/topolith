package io.github.rey5137.topolith.spring.boot.fixture.inventory;

import javax.sql.DataSource;

import liquibase.integration.spring.SpringLiquibase;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Pattern A migrations: the module runs its own changelog on its own DataSource, as an opt-in beside the entities. */
@Configuration(proxyBeanMethods = false)
public class InventoryLiquibaseConfiguration {

    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(name = "inventory.liquibase", havingValue = "own")
    public SpringLiquibase inventoryLiquibase(@Qualifier("inventory") DataSource dataSource) {
        return liquibase(dataSource);
    }

    /** The mistake: a default candidate, which would stop Boot's Liquibase from running the host's changelog. */
    @Bean
    @ConditionalOnProperty(name = "inventory.liquibase", havingValue = "candidate")
    public SpringLiquibase inventoryDefaultLiquibase(@Qualifier("inventory") DataSource dataSource) {
        return liquibase(dataSource);
    }

    private static SpringLiquibase liquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/modules/inventory/db.changelog-master.yaml");
        return liquibase;
    }
}
