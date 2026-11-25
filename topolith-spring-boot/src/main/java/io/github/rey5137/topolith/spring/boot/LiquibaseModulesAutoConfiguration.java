package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.ModuleMigrations.Tool;
import io.github.rey5137.topolith.spring.boot.TopologyResolver.Resolution;

import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.ui.UIServiceEnum;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanInitializationException;
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
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.StringUtils;

/**
 * Per-module Liquibase runs (D-32), ordered after Boot's Liquibase and Flyway auto-configurations so that Boot's
 * {@code @ConditionalOnMissingBean(SpringLiquibase)} never sees them. Each internal module that uses the host's
 * database and ships {@code db/modules/<id>/db.changelog-master.*} gets its own non-default {@code SpringLiquibase}
 * with the host's connection and schema settings and the tables {@code databasechangelog_<id>} and
 * {@code databasechangeloglock_<id>}. It runs after every host runner (Flyway or Liquibase) and after the runners of
 * the modules it requires, whichever their tool, even when the host switched its own Liquibase off. It is a
 * {@link ModuleRunner} wrapping the {@code SpringLiquibase}, so that Boot's initializer ordering, which puts every
 * Liquibase before every Flyway, does not apply to it. A host changelog that reaches a module's files is
 * TL0020. Only this file touches Liquibase.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
@ConditionalOnClass(name = {"liquibase.integration.spring.SpringLiquibase", "liquibase.change.DatabaseChange"})
@Import(LiquibaseModulesAutoConfiguration.ModuleChangelogsRegistrar.class)
class LiquibaseModulesAutoConfiguration {

    @Bean
    static BeanPostProcessor topolithLiquibaseChangelogCheck(Environment environment) {
        return new ChangelogCheck(environment);
    }

    /**
     * Runs the module's changelog when the bean is initialized. A {@link ModuleRunner}, not a
     * {@code SpringLiquibase}, so that {@link ModuleRunnerDetector} orders it after every other runner.
     */
    static final class ModuleLiquibase extends ModuleRunner
            implements InitializingBean, BeanNameAware, ResourceLoaderAware {
        private final SpringLiquibase liquibase;

        ModuleLiquibase(SpringLiquibase liquibase) {
            this.liquibase = liquibase;
        }

        @Override
        public void setBeanName(String name) {
            liquibase.setBeanName(name);
        }

        @Override
        public void setResourceLoader(ResourceLoader resourceLoader) {
            liquibase.setResourceLoader(resourceLoader);
        }

        @Override
        public void afterPropertiesSet() throws LiquibaseException {
            liquibase.afterPropertiesSet();
        }
    }

    static final class ModuleChangelogsRegistrar
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
            Set<String> ownDataSources = new TreeSet<>(ModuleBeans.contributors(factory, loader, resolution,
                    ModuleBeans.DATA_SOURCE));
            Map<String, Tool> runners = ModuleMigrations.runners(topology, loader, ownDataSources);
            if (!runners.containsValue(Tool.LIQUIBASE)) {
                return;
            }
            List<String> host = ModuleBeans.hostRunners(factory, loader, resolution);
            List<String> hostLiquibases = ModuleBeans.hostBeans(factory, loader, resolution, ModuleBeans.LIQUIBASE);
            String hostLiquibase = hostLiquibases.isEmpty() ? null : hostLiquibases.get(0);
            runners.forEach((id, tool) -> {
                if (tool != Tool.LIQUIBASE) {
                    return;
                }
                String changeLog = ModuleMigrations.location(id) + "/"
                        + ModuleMigrations.migrations(loader, id).master();
                RootBeanDefinition definition = new RootBeanDefinition(ModuleLiquibase.class,
                        () -> new ModuleLiquibase(moduleLiquibase(factory, environment, id, changeLog,
                                hostLiquibase)));
                definition.setDefaultCandidate(false);
                definition.setDependsOn(ModuleMigrations.dependsOn(topology, id, runners, host));
                registry.registerBeanDefinition(Tool.LIQUIBASE.runnerName(id), definition);
            });
        }

        /**
         * The module's Liquibase: a fresh runner that takes only the host's connection, schemas, contexts, label
         * filter and licence settings, read from the host's default {@code SpringLiquibase} when there is one and
         * otherwise from {@code spring.liquibase.*} (the host switched its own off), pointed at the module's master
         * file and tracking tables. Drop, checksum clearing, tag, rollback testing, parameters and the customizer
         * are never copied; the host's default DataSource is the fallback connection.
         */
        private static SpringLiquibase moduleLiquibase(ConfigurableListableBeanFactory factory,
                Environment environment, String id, String changeLog, String hostLiquibase) {
            SpringLiquibase module = new SpringLiquibase();
            DataSource dataSource = null;
            if (hostLiquibase != null) {
                SpringLiquibase host = factory.getBean(hostLiquibase, SpringLiquibase.class);
                dataSource = host.getDataSource();
                module.setDefaultSchema(host.getDefaultSchema());
                module.setLiquibaseSchema(host.getLiquibaseSchema());
                module.setLiquibaseTablespace(host.getLiquibaseTablespace());
                module.setContexts(host.getContexts());
                module.setLabelFilter(host.getLabelFilter());
                module.setAnalyticsEnabled(host.getAnalyticsEnabled());
                module.setLicenseKey(host.getLicenseKey());
                if (host.getUiService() != null) {
                    module.setUiService(host.getUiService());
                }
            } else {
                Binder binder = Binder.get(environment);
                module.setDefaultSchema(binder.bind("spring.liquibase.default-schema", String.class).orElse(null));
                module.setLiquibaseSchema(binder.bind("spring.liquibase.liquibase-schema", String.class)
                        .orElse(null));
                module.setLiquibaseTablespace(binder.bind("spring.liquibase.liquibase-tablespace", String.class)
                        .orElse(null));
                module.setContexts(joined(binder, "spring.liquibase.contexts"));
                module.setLabelFilter(joined(binder, "spring.liquibase.label-filter"));
                module.setAnalyticsEnabled(binder.bind("spring.liquibase.analytics-enabled", Boolean.class)
                        .orElse(null));
                module.setLicenseKey(binder.bind("spring.liquibase.license-key", String.class).orElse(null));
                binder.bind("spring.liquibase.ui-service", String.class)
                        .ifBound(name -> module.setUiService(UIServiceEnum.valueOf(name.toUpperCase())));
            }
            module.setDataSource(dataSource != null ? dataSource : factory.getBean(DataSource.class));
            module.setChangeLog(changeLog);
            module.setDatabaseChangeLogTable(ModuleMigrations.changelogTable(id));
            module.setDatabaseChangeLogLockTable(ModuleMigrations.lockTable(id));
            return module;
        }

        private static String joined(Binder binder, String name) {
            List<String> values = binder.bind(name, Bindable.listOf(String.class)).orElse(List.of());
            return values.isEmpty() ? null : StringUtils.collectionToCommaDelimitedString(values);
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

    /**
     * TL0020: a host changelog that would also run a module's files, checked before the bean's migration runs. Either
     * the host's master file is found more than once or in a module's artifact (a legacy JAR that ships
     * {@code db/changelog/db.changelog-master.yaml}), or parsing the changelog without a database (so that
     * {@code include} and {@code includeAll} resolve) yields a change set from {@code db/modules/} or from a module's
     * artifact.
     */
    private static final class ChangelogCheck implements BeanPostProcessor, BeanFactoryAware, BeanClassLoaderAware {
        private final Environment environment;
        private BeanFactory beanFactory;
        private ClassLoader classLoader;

        ChangelogCheck(Environment environment) {
            this.environment = environment;
        }

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
            if (!(bean instanceof SpringLiquibase liquibase) || ModuleMigrations.isModuleRunner(beanName)
                    || !(beanFactory instanceof ConfigurableListableBeanFactory factory)) {
                return bean;
            }
            Resolution resolution = TopologyResolver.resolve(beanFactory, environment, classLoader);
            if (factory.containsBeanDefinition(beanName)
                    && ModuleBeans.owner(factory.getBeanDefinition(beanName), resolution).isPresent()) {
                return bean;
            }
            String changeLog = liquibase.getChangeLog();
            String path = classPathPath(changeLog);
            if (path == null) {
                return bean;
            }
            List<Diagnostic> found = new ArrayList<>();
            try {
                List<URL> urls = Collections.list(classLoader.getResources(path));
                if (urls.isEmpty()) {
                    return bean;
                }
                shadowed(changeLog, path, urls, resolution, found);
                if (found.isEmpty()) {
                    includes(changeLog, path, resolution, found);
                }
            } catch (IOException | LiquibaseException e) {
                throw new BeanInitializationException("cannot read the Liquibase changelog '" + changeLog + "'", e);
            }
            if (!found.isEmpty()) {
                throw new TopolithException(found);
            }
            return bean;
        }

        /**
         * The class-path resource path of a changelog, or null for an explicit other scheme ({@code file:},
         * {@code http:}). A bare path and {@code classpath*:} are class-path paths, as Spring and Liquibase read them.
         */
        static String classPathPath(String changeLog) {
            if (changeLog == null) {
                return null;
            }
            String path = changeLog;
            if (path.startsWith("classpath*:")) {
                path = path.substring("classpath*:".length());
            } else if (path.startsWith("classpath:")) {
                path = path.substring("classpath:".length());
            } else if (path.matches("^[A-Za-z][A-Za-z0-9+.-]+:.*")) {
                return null;
            }
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            return path;
        }

        private void shadowed(String changeLog, String path, List<URL> urls, Resolution resolution,
                List<Diagnostic> found) {
            if (urls.size() > 1) {
                found.add(new Diagnostic(TopolithCode.TL0020, "the host's Liquibase changelog '" + changeLog
                        + "' is found " + urls.size() + " times on the class path: " + urls + ".",
                        "Remove the duplicate; a module's changelog belongs at " + ModuleMigrations.ROOT
                        + "<id>/" + ModuleMigrations.MASTER + "yaml."));
                return;
            }
            String owner = resolution.rootOwners().get(DescriptorDiscovery.root(urls.get(0).toString(), path));
            if (owner != null) {
                found.add(new Diagnostic(TopolithCode.TL0020, "the host's Liquibase changelog '" + changeLog
                        + "' is found in the artifact of module '" + owner + "' (" + urls.get(0) + ").",
                        "Move the module's changelog to " + ModuleMigrations.path(owner) + "/"
                        + ModuleMigrations.MASTER + "yaml."));
            }
        }

        private void includes(String changeLog, String path, Resolution resolution, List<Diagnostic> found)
                throws IOException, LiquibaseException {
            ClassLoaderResourceAccessor accessor = new ClassLoaderResourceAccessor(classLoader);
            DatabaseChangeLog parsed = ChangeLogParserFactory.getInstance().getParser(path, accessor)
                    .parse(path, new ChangeLogParameters(), accessor);
            Set<String> files = new TreeSet<>();
            for (ChangeSet changeSet : parsed.getChangeSets()) {
                files.add(changeSet.getFilePath());
            }
            Set<String> reported = new TreeSet<>();
            for (String file : files) {
                if (file.startsWith(ModuleMigrations.ROOT)) {
                    String id = file.substring(ModuleMigrations.ROOT.length()).split("/")[0];
                    if (reported.add(id)) {
                        found.add(new Diagnostic(TopolithCode.TL0020, "the host's Liquibase changelog '" + changeLog
                                + "' includes '" + file + "', which belongs to module '" + id + "'.",
                                "Narrow the host's include or includeAll to its own files; Topolith runs each "
                                + "module's changelog from " + ModuleMigrations.path(id) + "."));
                    }
                    continue;
                }
                for (URL url : Collections.list(classLoader.getResources(file))) {
                    String owner = resolution.rootOwners().get(DescriptorDiscovery.root(url.toString(), file));
                    if (owner != null && reported.add(owner)) {
                        found.add(new Diagnostic(TopolithCode.TL0020, "the host's Liquibase changelog '" + changeLog
                                + "' includes '" + file + "' from the artifact of module '" + owner + "' (" + url
                                + ").", "Move the module's changelog to " + ModuleMigrations.path(owner)
                                + ", or narrow the host's include."));
                    }
                }
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
