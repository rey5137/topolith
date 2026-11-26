package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/**
 * Adds the {@link ContractCallTranslator} to every external and loopback module's service proxy, and to no other group
 * (D-43, R-EXT-09), and keeps the external groups' client builders for the readiness version check.
 * Imported beside {@link ExternalHttpServicesRegistrar}, only when a module is external or loopback (INV-2).
 */
@Configuration(proxyBeanMethods = false)
class ExternalErrorsConfiguration {
    static final String CLIENTS_BEAN = "topolithExternalClients";

    @Bean(CLIENTS_BEAN)
    static ExternalClientBuilders topolithExternalClients() {
        return new ExternalClientBuilders();
    }

    @Bean
    RestClientHttpServiceGroupConfigurer topolithContractErrors(Topology topology, ExternalClientBuilders clients) {
        String[] externals = topology.idsOf(Mode.EXTERNAL).toArray(String[]::new);
        String[] callers = ExternalHttpServicesRegistrar.clientModules(topology).toArray(String[]::new);
        return groups -> {
            groups.filterByName(externals).forEachClient((group, builder) -> clients.put(group.name(), builder));
            groups.filterByName(callers).forEachProxyFactory((group, factory) -> factory
                    .proxyFactoryCustomizer((proxy, service) -> proxy.addAdvice(0, new ContractCallTranslator(
                            group.name(), topology.apiPackages(), service.getClassLoader()))));
        };
    }
}
