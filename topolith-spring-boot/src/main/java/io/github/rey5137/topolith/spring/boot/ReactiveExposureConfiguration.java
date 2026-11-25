package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * TL0028: exposure is served by Spring MVC only, so an exposed module on a reactive host fails startup (D-43, D-44).
 * Imported only when a module is exposed. Eager, so lazy initialization cannot skip it.
 */
@Configuration(proxyBeanMethods = false)
@Lazy(false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class ReactiveExposureConfiguration {
    ReactiveExposureConfiguration(TopologyResolver.Resolution resolution) {
        if (!resolution.exposed().isEmpty()) {
            throw new TopolithException(new Diagnostic(TopolithCode.TL0028, "module(s) " + resolution.exposed()
                    + " are exposed, but this is a reactive (WebFlux) application and exposure needs Spring MVC.",
                    "Remove topolith.modules.<id>.expose.enabled, or run the provider as a servlet application."));
        }
    }
}
