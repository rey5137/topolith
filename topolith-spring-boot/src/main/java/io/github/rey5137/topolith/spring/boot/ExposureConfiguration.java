package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.Set;

import io.github.rey5137.topolith.core.Topology;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Decides which routes of the internal modules a servlet host serves (D-44): the handlers of an exposed module are
 * mapped under its fixed {@code /<id>} prefix, those of every other internal module are not mapped at all. Imported by
 * the topology selector only when a module is internal and {@code DispatcherServlet} is present (INV-2). It is
 * ordered first, because the first path prefix whose predicate matches wins and a host's own prefix must not take
 * an exposed module's handlers from {@code /<id>}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ExposureConfiguration implements WebMvcConfigurer {
    private final Topology topology;
    private final Set<String> exposed;

    ExposureConfiguration(TopologyResolver.Resolution resolution) {
        this.topology = resolution.topology();
        this.exposed = resolution.exposed();
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        for (String id : exposed) {
            configurer.addPathPrefix("/" + id, type -> id.equals(ModuleHandlers.internalOwner(topology, type)));
        }
    }

    @Bean
    static ExposedEndpoints topolithExposedEndpoints(TopologyResolver.Resolution resolution) {
        return new ExposedEndpoints(resolution.exposed());
    }

    @Bean
    static BeanPostProcessor topolithUnexposedRoutes(BeanFactory beanFactory) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RequestMappingHandlerMapping mapping) {
                    TopologyResolver.Resolution resolution = beanFactory.getBean(TopologyResolver.RESOLUTION_BEAN,
                            TopologyResolver.Resolution.class);
                    new ArrayList<>(mapping.getHandlerMethods().entrySet()).forEach(entry -> {
                        String owner = ModuleHandlers.internalOwner(resolution.topology(),
                                entry.getValue().getBeanType());
                        if (owner != null && !resolution.exposed().contains(owner)) {
                            mapping.unregisterMapping(entry.getKey());
                        }
                    });
                }
                return bean;
            }
        };
    }

    /** The matcher bean, only when Spring Security's web module is present (INV-7). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.web.util.matcher.RequestMatcher")
    static class SecurityMatcher {
        @Bean
        ExposedRequestMatcher topolithExposedRequestMatcher(ExposedEndpoints endpoints) {
            return new ExposedRequestMatcher(endpoints);
        }
    }
}
