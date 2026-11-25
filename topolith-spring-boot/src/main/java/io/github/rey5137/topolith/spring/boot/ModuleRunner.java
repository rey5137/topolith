package io.github.rey5137.topolith.spring.boot;

/**
 * Marks a migration runner that Topolith registers for a module, whichever its tool (D-32). It is neither a
 * {@code SpringLiquibase} nor a {@code FlywayMigrationInitializer}, so Boot's own initializer detectors, which run
 * every Liquibase before every Flyway initializer, leave it alone; {@link ModuleRunnerDetector} puts every module
 * runner after both, and the runners' own dependsOn order them among themselves. No optional types.
 */
abstract class ModuleRunner {
}
