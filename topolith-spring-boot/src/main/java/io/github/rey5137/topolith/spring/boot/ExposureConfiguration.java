package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

import io.github.rey5137.topolith.core.Topology;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.ClassUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Decides which routes of the internal modules a servlet host serves (D-44): the handlers of an exposed or loopback
 * module (D-76) are mapped under its fixed {@code /<id>} prefix, those of every other internal module are not mapped at
 * all, and every contract method of an exposed or loopback module must have such a route (D-126). Imported by
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
    private final ContextRoute context;
    private final ListedContractRoutes routes;
    private final UnauthenticatedRelay relayWarning;

    ExposureConfiguration(TopologyResolver.Resolution resolution, ObjectProvider<ContextPropagation> context,
            ListedContractRoutes routes, ConfigurableBeanFactory beans) {
        this.topology = resolution.topology();
        this.exposed = resolution.exposed();
        this.routes = routes;
        ContextPropagation propagation = context.getIfAvailable();
        this.context = propagation == null || exposed.isEmpty() ? null : new ContextRoute(propagation);
        this.relayWarning = !exposed.isEmpty() && ClassUtils.isPresent(
                "org.springframework.security.core.context.SecurityContextHolder", beans.getBeanClassLoader())
                ? new UnauthenticatedRelay() : null;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        for (String id : exposed) {
            configurer.addPathPrefix("/" + id, type -> id.equals(ModuleHandlers.internalOwner(topology, type)));
        }
    }

    /**
     * Marks each request to a module route with its handler, for the call observation (D-86), and makes the caller's
     * MDC values and tenant current around it (R-EXT-12).
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        InterceptorRegistration registration = registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws IOException {
                if (handler instanceof HandlerMethod method) {
                    String owner = routes.internalOwner(topology, method);
                    if (owner != null && exposed.contains(owner)) {
                        if (relayWarning != null) {
                            relayWarning.check(request, owner);
                        }
                        RouteMark.mark(request, method, routes.owner(method) != null);
                        if (context != null && !context.open(request, response, owner)) {
                            return false;
                        }
                        response.setHeader("Topolith-Module", owner);
                        return true;
                    }
                }
                return true;
            }

            /** The container thread goes back to its pool now; the async dispatch opens again (R-EXT-12). */
            @Override
            public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                    Object handler) {
                if (context != null) {
                    context.close(request);
                }
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                    Exception failure) {
                if (context != null) {
                    context.close(request);
                }
            }
        });
        // Before a host's own interceptors, so they see the sent MDC and tenant (R-EXT-12).
        registration.order(Ordered.HIGHEST_PRECEDENCE);
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
                        String owner = beanFactory.getBean(ListedContractRoutes.class)
                                .internalOwner(resolution.topology(), entry.getValue());
                        if (owner != null && !resolution.exposed().contains(owner)) {
                            mapping.unregisterMapping(entry.getKey());
                        }
                    });
                }
                return bean;
            }
        };
    }

    @Bean
    static ListedContractRoutes topolithListedContractRoutes() {
        return new ListedContractRoutes();
    }

    @Bean
    static BeanPostProcessor topolithListedRouteReturnValues(ListedContractRoutes routes) {
        return new ListedRouteReturnValues(routes);
    }

    @Bean
    static ListedRouteHints topolithListedRouteHints() {
        return new ListedRouteHints();
    }

    /** Eager, so lazy initialization cannot skip the route coverage check (D-126). */
    @Bean
    @Lazy(false)
    static RouteCoverageCheck topolithRouteCoverage(ListableBeanFactory beanFactory, ListedContractRoutes registered) {
        return new RouteCoverageCheck(beanFactory, registered);
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
