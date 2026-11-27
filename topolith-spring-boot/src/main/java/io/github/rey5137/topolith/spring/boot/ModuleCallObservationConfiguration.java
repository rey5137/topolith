package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Topology;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link ModuleCallObserver} (R-EXT-11). Imported by the topology selector only when an internal,
 * loopback or external module exists and Micrometer Observation is present (INV-2).
 */
@Configuration(proxyBeanMethods = false)
class ModuleCallObservationConfiguration {
    @Bean
    static ModuleCallObserver topolithModuleCallObserver(BeanFactory beanFactory, Topology topology) {
        return new ModuleCallObserver(beanFactory, topology);
    }
}
