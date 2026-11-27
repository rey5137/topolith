package io.github.rey5137.topolith.spring.boot;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link ContractTransactionBoundary}, the advice of an internal module's provider beans (D-47, D-86).
 * Imported by the topology selector only when a module is internal or loopback (INV-2).
 */
@Configuration(proxyBeanMethods = false)
class ContractBoundaryConfiguration {
    @Bean
    static BeanPostProcessor topolithContractBoundary(BeanFactory beanFactory) {
        return new ContractTransactionBoundary(beanFactory);
    }
}
