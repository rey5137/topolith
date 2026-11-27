package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.Topology;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/**
 * Adds the {@link ContractCallTranslator}, and the call observation in front of it, to every external and loopback
 * module's service proxy, and to no other group (D-43, R-EXT-09), and keeps the external groups' client builders for
 * the readiness version check.
 * Imported beside {@link ExternalHttpServicesRegistrar}, only when a module is external or loopback (INV-2).
 */
@Configuration(proxyBeanMethods = false)
class ExternalErrorsConfiguration {
    static final String CLIENTS_BEAN = "topolithExternalClients";

    @Bean(CLIENTS_BEAN)
    static ExternalClientBuilders topolithExternalClients() {
        return new ExternalClientBuilders();
    }

    /**
     * Ordered before Spring Cloud's circuit-breaker configurer (15), so its decorator, added first, is the innermost
     * and runs on the breaker's thread (R-EXT-11).
     */
    @Bean
    @Order(10)
    RestClientHttpServiceGroupConfigurer topolithObservationHandoff(Topology topology,
            ObjectProvider<CallObserver> observers) {
        String[] callers = ExternalHttpServicesRegistrar.clientModules(topology).toArray(String[]::new);
        return groups -> {
            CallObserver observer = observers.getIfAvailable();
            if (observer != null) {
                groups.filterByName(callers).forEachProxyFactory((group, factory) -> factory
                        .exchangeAdapterDecorator(ObservationHandoff.decorator(observer)));
            }
        };
    }

    @Bean
    RestClientHttpServiceGroupConfigurer topolithContractErrors(Topology topology, ExternalClientBuilders clients,
            ObjectProvider<CallObserver> observers, ObjectProvider<ContextPropagation> propagation,
            ObjectProvider<OutgoingAuth> auth) {
        String[] externals = topology.idsOf(Mode.EXTERNAL).toArray(String[]::new);
        String[] callers = ExternalHttpServicesRegistrar.clientModules(topology).toArray(String[]::new);
        ContextPropagation found = propagation.getIfAvailable();
        ContextPropagation context = found == null || !found.active() ? null : found;
        return groups -> {
            groups.filterByName(externals).forEachClient((group, builder) -> clients.put(group.name(), builder));
            groups.filterByName(callers).forEachProxyFactory((group, factory) -> {
                OutgoingAuth hooks = auth.getIfAvailable();
                boolean relay = hooks != null && hooks.relays(group.name());
                CallObserver current = observers.getIfAvailable();
                if (context != null || relay || current != null) {
                    // read on the calling thread, which a circuit breaker's exchange may not be on (R-EXT-12, D-93)
                    factory.httpRequestValuesProcessor((method, parameters, arguments, values) -> {
                        if (context != null) {
                            context.headers().forEach(values::addHeader);
                        }
                        if (current != null) {
                            ObservationHandoff.capture(current, values);
                        }
                        String token = relay ? BearerToken.current() : null;
                        if (token != null) {
                            values.addAttribute(RelayHook.TOKEN, token);
                        }
                    });
                }
                factory.proxyFactoryCustomizer((proxy, service) -> {
                    proxy.addAdvice(0, new ContractCallTranslator(group.name(), topology.apiPackages(),
                            service.getClassLoader()));
                    // in front of the translation, so the observation ends after it (R-EXT-11, D-86)
                    if (current != null) {
                        Mode mode = topology.mode(group.name());
                        proxy.addAdvice(0, (MethodInterceptor) invocation -> invocation.getMethod()
                                .getDeclaringClass() == Object.class ? invocation.proceed()
                                : current.observe(group.name(), mode, service, invocation.getMethod().getName(),
                                        invocation::proceed));
                    }
                });
            });
        };
    }
}
