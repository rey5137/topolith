package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceRefresh;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.rey5137.topolith.spring.boot.internal.ResilienceModules;
import io.github.rey5137.topolith.spring.boot.internal.ResilienceSettings;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
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

    /** The inputs of the retry and breaker of {@code topolith-spring-cloud}: external and loopback modules only. */
    @Bean
    static ResilienceModules topolithResilienceModules(Environment environment, BeanFactory beanFactory) {
        TopologyResolver.Resolution resolution = beanFactory.getBean(TopologyResolver.RESOLUTION_BEAN,
                TopologyResolver.Resolution.class);
        Binder binder = Binder.get(environment);
        Map<String, ResilienceModules.Module> modules = new TreeMap<>();
        for (String id : ExternalHttpServicesRegistrar.clientModules(resolution.topology())) {
            Map<String, String> mapped = resolution.httpInterfaces().getOrDefault(id, Map.of());
            Set<String> services = new TreeSet<>();
            resolution.contracts().getOrDefault(id, Set.of()).forEach(name -> services.add(mapped
                    .getOrDefault(name, name)));
            modules.put(id, new ResilienceModules.Module(ResilienceSettings.retry(binder, id),
                    ResilienceSettings.breaker(binder, id), resolution.idempotent().getOrDefault(id, Set.of()),
                    mapped, services));
        }
        return new ResilienceModules(modules);
    }

    /**
     * TL0041 when a module turns retry or the circuit breaker on while {@code topolith-spring-cloud}'s resilience
     * configuration is not active (missing or excluded): the class-presence check names a missing jar early, this one
     * judges the running state, so nothing starts with the policy silently absent.
     */
    @Bean
    static SmartInitializingSingleton topolithResilienceActive(ResilienceModules modules,
            ObjectProvider<ResilienceRefresh> hook) {
        return () -> {
            if (hook.getIfAvailable() != null) {
                return;
            }
            List<Diagnostic> problems = new ArrayList<>();
            for (String id : modules.ids()) {
                ResilienceModules.Module module = modules.module(id);
                if (module.retry().active()) {
                    problems.add(ResilienceSettings.notActive(id, "retry.max-attempts"));
                }
                if (module.breaker().enabled()) {
                    problems.add(ResilienceSettings.notActive(id, "circuit-breaker.enabled"));
                }
            }
            if (!problems.isEmpty()) {
                throw new TopolithException(problems);
            }
        };
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
                        String token = relay ? hooks.relayToken(group.name()) : null;
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
