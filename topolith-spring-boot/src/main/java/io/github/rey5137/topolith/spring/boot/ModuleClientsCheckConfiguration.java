package io.github.rey5137.topolith.spring.boot;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/** Eagerly initializes customizers even when no HTTP group exists or host beans are lazy. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer")
class ModuleClientsCheckConfiguration {
    @Bean
    @Lazy(false)
    static SmartInitializingSingleton topolithModuleClientsCheck(ConfigurableListableBeanFactory factory) {
        return () -> factory.getBeansOfType(RestClientHttpServiceGroupConfigurer.class);
    }
}
