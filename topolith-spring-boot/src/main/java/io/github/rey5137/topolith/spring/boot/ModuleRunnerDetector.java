package io.github.rey5137.topolith.spring.boot;

import java.util.Set;

import org.springframework.core.Ordered;
import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDatabaseInitializerDetector;

/**
 * Registered in {@code META-INF/spring.factories}. Boot makes each batch of database initializers wait for every
 * earlier batch and makes the beans that need the database (the entity-manager factory, for one) wait for all of
 * them. Liquibase is batch 0, Flyway's initializers batch 1 and the host's SQL-script initializers a later one; the
 * module runners come after all of them, so a module's migrations run after every host runner, scripts included, and
 * a host Flyway never meets a schema a module has already filled.
 */
public final class ModuleRunnerDetector extends AbstractBeansOfTypeDatabaseInitializerDetector {
    /**
     * After Flyway's {@code FlywayMigrationInitializer} detector (1) and Boot's script initializers
     * ({@code spring.sql.init} and the Batch, Quartz and Session schemas, {@code LOWEST_PRECEDENCE - 100}), and before
     * the beans that need the database, which Boot orders last.
     */
    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 50;

    @Override
    protected Set<Class<?>> getDatabaseInitializerBeanTypes() {
        return Set.of(ModuleRunner.class);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
