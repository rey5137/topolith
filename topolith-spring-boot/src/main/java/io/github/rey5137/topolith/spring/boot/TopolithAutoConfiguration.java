package io.github.rey5137.topolith.spring.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationListener;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.Ordered;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Bean
    ApplicationListener<ApplicationReadyEvent> topolithVersionReadiness(BeanFactory beanFactory) {
        AtomicBoolean logged = new AtomicBoolean();
        return event -> {
            if (logged.compareAndSet(false, true)) {
                TopologyResolver.Resolution resolution = beanFactory.getBean(TopologyResolver.RESOLUTION_BEAN,
                        TopologyResolver.Resolution.class);
                resolution.versionWarnings().forEach(LoggerFactory.getLogger(TopolithAutoConfiguration.class)::warn);
            }
        };
    }
}
