package io.github.rey5137.topolith.spring.boot;

import java.util.List;
import java.util.Set;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.boot.autoconfigure.AutoConfigurationImportEvent;
import org.springframework.boot.autoconfigure.AutoConfigurationImportListener;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Fails startup with TL0042 when an auto-configuration that activates modules is excluded while a module is
 * {@code internal} or {@code loopback} (R-MODE-02, D-111): without it the module's beans would silently not load. It
 * reads the exclusions of each auto-configuration import (the property, {@code exclude} and {@code excludeName}), so a
 * test slice that never includes Topolith's auto-configuration is not checked. Registered in
 * {@code META-INF/spring.factories}.
 */
@Incubating
public class TopolithExclusionCheck
        implements AutoConfigurationImportListener, BeanFactoryAware, EnvironmentAware, BeanClassLoaderAware {
    /**
     * The auto-configurations whose exclusion stops an internal or loopback module's contributions (D-111). An
     * explicit list, not a name prefix: excluding one that activates no module, such as the refresh or the resilience
     * configuration of topolith-spring-cloud, is allowed (TL0041 covers the resilience one).
     */
    private static final Set<String> ACTIVATING = Set.of(
            // imports the module component scan and the module-owned beans
            "io.github.rey5137.topolith.spring.boot.TopolithAutoConfiguration",
            // registers each module's entities and entity manager
            "io.github.rey5137.topolith.spring.boot.JpaModulesAutoConfiguration",
            // register each module's repositories and mappers
            "io.github.rey5137.topolith.spring.boot.JpaRepositoriesModulesAutoConfiguration",
            "io.github.rey5137.topolith.spring.boot.JdbcRepositoriesModulesAutoConfiguration",
            "io.github.rey5137.topolith.spring.boot.R2dbcRepositoriesModulesAutoConfiguration",
            "io.github.rey5137.topolith.spring.boot.MongoRepositoriesModulesAutoConfiguration",
            "io.github.rey5137.topolith.spring.boot.MyBatisRepositoriesModulesAutoConfiguration",
            // run each module's own migrations
            "io.github.rey5137.topolith.spring.boot.FlywayModulesAutoConfiguration",
            "io.github.rey5137.topolith.spring.boot.LiquibaseModulesAutoConfiguration");

    private BeanFactory beanFactory;
    private Environment environment;
    private ClassLoader classLoader;

    @Override
    public void onAutoConfigurationImportEvent(AutoConfigurationImportEvent event) {
        List<String> excluded = event.getExclusions().stream().filter(ACTIVATING::contains).sorted().toList();
        if (excluded.isEmpty()) {
            return;
        }
        Topology topology;
        try {
            // resolved for this check only: nothing is registered, and a topology error is not this check's to report
            topology = TopologyResolver.requested(environment, classLoader);
        } catch (TopolithException unrelated) {
            return;
        }
        if (!topology.internal().isEmpty()) {
            throw new TopolithException(excluded.stream().map(name -> new Diagnostic(TopolithCode.TL0042,
                    "the auto-configuration " + name + " is excluded while module(s) " + topology.internal()
                            + " are internal or loopback, so their beans would not load.",
                    "Remove the exclusion, or set those modules to none or external.")).toList());
        }
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }
}
