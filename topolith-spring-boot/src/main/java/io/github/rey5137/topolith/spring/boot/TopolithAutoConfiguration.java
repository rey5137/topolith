package io.github.rey5137.topolith.spring.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.Ordered;

/**
 * Activates the internal modules (D-30). It is ordered first and imports a non-deferred selector, so a module's beans
 * are parsed before the conditions of every later auto-configuration are evaluated.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@Import({TopolithModuleImportSelector.class, TopolithEndpointConfiguration.class})
public class TopolithAutoConfiguration {

    /** The persistence checks that need no persistence library (D-32). */
    @Bean
    static TopolithPersistenceCheck topolithPersistenceCheck() {
        return new TopolithPersistenceCheck();
    }

    /** Eager, so lazy initialization cannot skip the readiness checks (D-24). */
    @Bean
    @Lazy(false)
    VersionReadiness topolithVersionReadiness(BeanFactory beanFactory) {
        return new VersionReadiness(beanFactory);
    }
}
