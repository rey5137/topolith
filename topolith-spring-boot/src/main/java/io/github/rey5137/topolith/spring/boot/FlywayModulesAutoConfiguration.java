package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.ModuleMigrations.Tool;
import io.github.rey5137.topolith.spring.boot.TopologyResolver.Resolution;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Per-module Flyway runs (D-32), ordered after Boot's Flyway and Liquibase auto-configurations. Each internal module
 * that uses the host's database and ships Flyway scripts under {@code classpath:db/modules/<id>} gets its own
 * {@link ModuleRunner} with the host's connection settings, that location and the history table
 * {@code flyway_schema_history_<id>}. It runs after every host runner (Flyway or Liquibase) and after the runners of
 * the modules it requires, whichever their tool, even when the host switched its own Flyway off. A module with exactly
 * one DataSource of its own and no runner of its own gets the same runner on that DataSource instead (D-112). A host
 * location that reaches a module's scripts is TL0020. Only this file touches Flyway.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration"})
@ConditionalOnClass(name = "org.flywaydb.core.Flyway")
@Import(FlywayModulesAutoConfiguration.ModuleMigrationsRegistrar.class)
class FlywayModulesAutoConfiguration {

    @Bean
    static BeanPostProcessor topolithFlywayLocationCheck(Environment environment) {
        return new LocationCheck(environment);
    }

    static String initializerName(String id) {
        return "topolith.flyway." + id;
    }

    /**
     * Migrates when the bean is initialized. A {@link ModuleRunner}, not a {@code FlywayMigrationInitializer}, so that
     * {@link ModuleRunnerDetector} orders it after every host runner and every other module runner.
     */
    static final class ModuleFlyway extends ModuleRunner implements InitializingBean {
        private final Flyway flyway;

        ModuleFlyway(Flyway flyway) {
            this.flyway = flyway;
        }

        @Override
        public void afterPropertiesSet() {
            flyway.migrate();
        }
    }

    static final class ModuleMigrationsRegistrar
            implements ImportBeanDefinitionRegistrar, BeanFactoryAware, EnvironmentAware {
        private BeanFactory beanFactory;
        private Environment environment;

        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            if (!(registry instanceof ConfigurableListableBeanFactory factory)) {
                return;
            }
            ClassLoader loader = factory.getBeanClassLoader();
            Resolution resolution = TopologyResolver.resolve(beanFactory, environment, loader);
            Topology topology = resolution.topology();
            Map<String, List<String>> ownDataSources = ModuleBeans.dataSourcesByModule(factory, loader, resolution);
            Map<String, Tool> runners = ModuleMigrations.runners(topology, loader, ownDataSources,
                    ModuleBeans.ownRunners(factory, loader, resolution),
                    ModuleBeans.connectionFactoryOnly(factory, loader, resolution));
            if (!runners.containsValue(Tool.FLYWAY)) {
                return;
            }
            List<String> host = ModuleBeans.hostRunners(factory, loader, resolution);
            List<String> hostFlyways = ModuleBeans.hostBeans(factory, loader, resolution, ModuleBeans.FLYWAY);
            String hostFlyway = hostFlyways.isEmpty() ? null : hostFlyways.get(0);
            runners.forEach((id, tool) -> {
                if (tool != Tool.FLYWAY) {
                    return;
                }
                String own = ModuleMigrations.dataSource(id, ownDataSources);
                RootBeanDefinition definition = new RootBeanDefinition(ModuleFlyway.class, () -> new ModuleFlyway(
                        own == null ? moduleFlyway(factory, environment, loader, id, hostFlyway)
                                : ownFlyway(factory.getBean(own, DataSource.class), loader, id)));
                definition.setDefaultCandidate(false);
                definition.setDependsOn(ModuleMigrations.dependsOn(topology, id, runners, host));
                registry.registerBeanDefinition(Tool.FLYWAY.runnerName(id), definition);
            });
        }

        /**
         * The module's Flyway: a fresh configuration that takes only the host's connection, schema, encoding,
         * placeholder, validation and transaction settings (D-32), pointed at the module's location and history
         * table. Version-selecting settings (target, cherry pick, skip, out of order, ignore patterns), the
         * resource and class providers, callbacks, Java migrations and file naming are the module's own. The schema
         * already holds the host's tables, so the history table is baselined at version 0 and every module script
         * still runs. With no host Flyway bean (the host switched its own off), the connection is the default
         * DataSource and the settings are the few {@code spring.flyway.*} keys below.
         */
        private static Flyway moduleFlyway(ConfigurableListableBeanFactory factory, Environment environment,
                ClassLoader loader, String id, String hostFlyway) {
            FluentConfiguration module = new FluentConfiguration(loader);
            if (hostFlyway == null) {
                Binder binder = Binder.get(environment);
                module.dataSource(factory.getBean(DataSource.class))
                        .schemas(binder.bind("spring.flyway.schemas", Bindable.listOf(String.class))
                                .orElse(List.of()).toArray(String[]::new))
                        .defaultSchema(binder.bind("spring.flyway.default-schema", String.class).orElse(null))
                        .createSchemas(binder.bind("spring.flyway.create-schemas", Boolean.class).orElse(true))
                        .installedBy(binder.bind("spring.flyway.installed-by", String.class).orElse(null));
                return module.locations(ModuleMigrations.location(id)).table(ModuleMigrations.table(id))
                        .baselineOnMigrate(true).baselineVersion("0").load();
            }
            Configuration host = factory.getBean(hostFlyway, Flyway.class).getConfiguration();
            if (host.getDataSource() != null) {
                module.dataSource(host.getDataSource());
            } else {
                module.dataSource(host.getUrl(), host.getUser(), host.getPassword());
            }
            return module.schemas(host.getSchemas()).defaultSchema(host.getDefaultSchema())
                    .createSchemas(host.isCreateSchemas()).tablespace(host.getTablespace())
                    .encoding(host.getEncoding()).detectEncoding(host.isDetectEncoding())
                    .placeholderReplacement(host.isPlaceholderReplacement()).placeholders(host.getPlaceholders())
                    .placeholderPrefix(host.getPlaceholderPrefix()).placeholderSuffix(host.getPlaceholderSuffix())
                    .placeholderSeparator(host.getPlaceholderSeparator())
                    .validateOnMigrate(host.isValidateOnMigrate())
                    .validateMigrationNaming(host.isValidateMigrationNaming())
                    .cleanDisabled(host.isCleanDisabled()).connectRetries(host.getConnectRetries())
                    .connectRetriesInterval(host.getConnectRetriesInterval()).lockRetryCount(host.getLockRetryCount())
                    .initSql(host.getInitSql()).installedBy(host.getInstalledBy())
                    .executeInTransaction(host.isExecuteInTransaction()).mixed(host.isMixed())
                    .group(host.isGroup()).jdbcProperties(host.getJdbcProperties())
                    .locations(ModuleMigrations.location(id)).table(ModuleMigrations.table(id))
                    .baselineOnMigrate(true).baselineVersion("0").load();
        }

        /**
         * The Flyway of a module on its own DataSource (D-112): none of the host's settings, which describe another
         * database, only the module's location and history table, baselined at version 0 as on the host's.
         */
        private static Flyway ownFlyway(DataSource dataSource, ClassLoader loader, String id) {
            return new FluentConfiguration(loader).dataSource(dataSource).locations(ModuleMigrations.location(id))
                    .table(ModuleMigrations.table(id)).baselineOnMigrate(true).baselineVersion("0").load();
        }

        @Override
        public void setBeanFactory(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }
    }

    /** TL0020: a host Flyway whose locations would also run a module's scripts, before its initializer migrates. */
    private static final class LocationCheck implements BeanPostProcessor, BeanFactoryAware, BeanClassLoaderAware {
        private final Environment environment;
        private BeanFactory beanFactory;
        private ClassLoader classLoader;

        LocationCheck(Environment environment) {
            this.environment = environment;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            if (!(bean instanceof Flyway flyway) || !(beanFactory instanceof ConfigurableListableBeanFactory factory)) {
                return bean;
            }
            Resolution resolution = TopologyResolver.resolve(beanFactory, environment, classLoader);
            if (factory.containsBeanDefinition(beanName)
                    && ModuleBeans.owner(factory.getBeanDefinition(beanName), resolution).isPresent()) {
                return bean;
            }
            List<Diagnostic> found = new ArrayList<>();
            for (Location location : flyway.getConfiguration().getLocations()) {
                if (location.isClassPath()) {
                    check(location.getRootPath(), resolution, found);
                }
            }
            if (!found.isEmpty()) {
                throw new TopolithException(found);
            }
            return bean;
        }

        private void check(String root, Resolution resolution, List<Diagnostic> found) {
            Set<String> reported = new TreeSet<>();
            for (String id : new TreeSet<>(resolution.topology().modulePackages().keySet())) {
                if (ModuleMigrations.reaches(root, id) && ModuleMigrations.hasFlywayScripts(classLoader, id)) {
                    reported.add(id);
                    found.add(new Diagnostic(TopolithCode.TL0020, "the host's Flyway location 'classpath:" + root
                            + "' would also run the migrations of module '" + id + "' under "
                            + ModuleMigrations.path(id) + ".", "Narrow the host's spring.flyway.locations to its own "
                            + "scripts; Topolith runs each module's scripts from " + ModuleMigrations.location(id)
                            + "."));
                }
            }
            if (root.isEmpty()) {
                return;
            }
            try {
                for (URL url : Collections.list(classLoader.getResources(root))) {
                    String owner = resolution.rootOwners().get(DescriptorDiscovery.root(url.toString(), root));
                    if (owner != null && !reported.contains(owner)) {
                        found.add(new Diagnostic(TopolithCode.TL0020, "the host's Flyway location 'classpath:" + root
                                + "' reaches scripts in the artifact of module '" + owner + "' (" + url + ").",
                                "Move the module's scripts to " + ModuleMigrations.path(owner)
                                + ", or narrow the host's spring.flyway.locations."));
                    }
                }
            } catch (IOException e) {
                throw new TopolithException(new Diagnostic(TopolithCode.TL0020, "the Flyway location 'classpath:"
                        + root + "' cannot be read (" + e.getMessage() + ").", "Check that the JAR or directory "
                        + "is readable."));
            }
        }

        @Override
        public void setBeanFactory(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void setBeanClassLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }
    }
}
