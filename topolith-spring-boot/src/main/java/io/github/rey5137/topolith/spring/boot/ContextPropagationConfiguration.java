package io.github.rey5137.topolith.spring.boot;

import java.util.List;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Registers the {@link ContextPropagation} (R-EXT-12). Imported by the topology selector only when a module is
 * internal, loopback or external (INV-2). Whether anything propagates ({@link ContextPropagation#active()}) is decided
 * when the bean is created, once every bean definition is registered, so a {@link TenantContext} from an
 * auto-configuration ordered after Topolith's counts; the client and provider halves look the bean up.
 */
@Configuration(proxyBeanMethods = false)
class ContextPropagationConfiguration {
    @Bean
    static ContextPropagation topolithContextPropagation(Environment environment, ListableBeanFactory beans,
            ObjectProvider<TenantContext> tenants) {
        ClassLoader loader = beans instanceof ConfigurableBeanFactory configurable
                ? configurable.getBeanClassLoader() : ClassUtils.getDefaultClassLoader();
        return new ContextPropagation(keys(environment), beans.getBeanNamesForType(TenantContext.class, true,
                false).length, ClassUtils.isPresent("org.slf4j.MDC", loader), tenants);
    }

    static List<String> keys(Environment environment) {
        return Binder.get(environment).bind(ContextPropagation.KEYS_PROPERTY, Bindable.listOf(String.class))
                .orElse(List.of());
    }
}
