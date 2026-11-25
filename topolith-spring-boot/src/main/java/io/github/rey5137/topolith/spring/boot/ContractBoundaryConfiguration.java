package io.github.rey5137.topolith.spring.boot;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link ContractTransactionBoundary} (D-47). Imported by the topology selector only when a module is
 * internal and spring-tx is present (INV-2).
 */
@Configuration(proxyBeanMethods = false)
class ContractBoundaryConfiguration {
    @Bean
    static BeanPostProcessor topolithContractBoundary(BeanFactory beanFactory) {
        return new ContractTransactionBoundary(beanFactory);
    }
}
