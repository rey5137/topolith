package io.github.rey5137.topolith.spring.boot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;

/**
 * Serves {@code GET /<id>/.topolith/api-version} for each exposed module: its descriptor's {@code api-version} as
 * plain text, which a caller's readiness check reads (R-VAL-04, AC-VAL-06). Imported only when a module is exposed.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ExposedApiVersionConfiguration {
    /** The path under {@code /<id>} that answers the module's {@code api-version}. */
    static final String VERSION_PATH = "/.topolith/api-version";

    /**
     * Its own mapping, ahead of {@code RequestMappingHandlerMapping}, so a module's wildcard route such as
     * {@code /{a}/{b}} cannot shadow the endpoint. The router is not a bean, so Boot's mapping never sees it.
     */
    @Bean
    RouterFunctionMapping topolithApiVersions(TopologyResolver.Resolution resolution) {
        RouterFunctions.Builder routes = RouterFunctions.route();
        resolution.exposed().forEach(id -> {
            String version = resolution.apiVersions().get(id);
            if (version != null) {
                routes.GET("/" + id + VERSION_PATH,
                        request -> ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).body(version));
            }
        });
        RouterFunctionMapping mapping = new RouterFunctionMapping(routes.build());
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return mapping;
    }
}
