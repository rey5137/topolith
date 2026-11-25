package io.github.rey5137.topolith.spring.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.core.Ordered;

/**
 * Activates the internal modules (D-30). It is ordered first and imports a non-deferred selector, so a module's beans
 * are parsed before the conditions of every later auto-configuration are evaluated.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@Import({TopolithModuleImportSelector.class, TopolithEndpointConfiguration.class,
        ModuleClientsCheckConfiguration.class})
public class TopolithAutoConfiguration {

    @Bean
    static TopolithBoundaryCheck topolithBoundaryCheck() {
        return new TopolithBoundaryCheck();
    }

    /** The persistence checks that need no persistence library (D-32). */
    @Bean
    static TopolithPersistenceCheck topolithPersistenceCheck() {
        return new TopolithPersistenceCheck();
    }

    /** What a refresh left behind, read by the actuator endpoint (D-50). */
    @Bean
    static TopolithRefreshStatus topolithRefreshStatus() {
        return new TopolithRefreshStatus();
    }

    /** Eager, so a refresh is compared with the environment the application started with. */
    @Bean
    @Lazy(false)
    TopolithRefresh topolithRefresh(Environment environment, Topology topology, TopolithRefreshStatus status,
            ObjectProvider<ExternalClientEndpoints> endpoints) {
        return new TopolithRefresh(environment, topology, status, endpoints);
    }

    /** Eager, so lazy initialization cannot skip the readiness checks (D-24). */
    @Bean
    @Lazy(false)
    VersionReadiness topolithVersionReadiness(BeanFactory beanFactory) {
        return new VersionReadiness(beanFactory);
    }
}
