package io.github.rey5137.topolith.spring.boot;

import java.util.List;

import io.github.rey5137.topolith.core.Topology;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

/**
 * Puts the {@link ModuleExceptionResolver} first among Spring MVC's exception resolvers, ahead of
 * {@code ExceptionHandlerExceptionResolver} (D-43). Imported by the topology selector only when a module is internal
 * and {@code DispatcherServlet} is present, and active only in a servlet web application (INV-2).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ModuleErrorsConfiguration implements WebMvcConfigurer {
    private final Topology topology;
    private final BeanFactory beanFactory;

    ModuleErrorsConfiguration(Topology topology, BeanFactory beanFactory) {
        this.topology = topology;
        this.beanFactory = beanFactory;
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        ExceptionHandlerExceptionResolver handlers = resolvers.stream()
                .filter(ExceptionHandlerExceptionResolver.class::isInstance)
                .map(ExceptionHandlerExceptionResolver.class::cast).findFirst().orElse(null);
        resolvers.add(0, new ModuleExceptionResolver(topology, beanFactory.getBean(TopologyResolver.RESOLUTION_BEAN,
                TopologyResolver.Resolution.class),
                () -> handlers == null ? List.of() : handlers.getMessageConverters()));
    }
}
