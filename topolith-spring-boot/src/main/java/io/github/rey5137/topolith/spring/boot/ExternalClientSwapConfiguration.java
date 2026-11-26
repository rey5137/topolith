package io.github.rey5137.topolith.spring.boot;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.autoconfigure.HttpClientProperties;
import org.springframework.boot.http.client.autoconfigure.HttpClientSettingsPropertyMapper;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/**
 * Installs the swappable URI and request factories in every external group's client (D-50). The configurer keeps the
 * default, lowest order so it runs after Boot's property and LoadBalancer's configurers and replaces only the two
 * settings Topolith owns. Imported beside {@link ExternalErrorsConfiguration}, only when a module is external (INV-2)
 * and Boot's HTTP client support is present.
 */
@Configuration(proxyBeanMethods = false)
class ExternalClientSwapConfiguration {
    @Bean
    static ExternalClientEndpoints topolithExternalEndpoints(ConfigurableEnvironment environment,
            BeanFactory beanFactory, TopolithRefreshStatus status) {
        return new ExternalClientEndpoints(environment, beanFactory, status);
    }

    @Bean
    RestClientHttpServiceGroupConfigurer topolithExternalSwap(Topology topology, ExternalClientEndpoints endpoints,
            ObjectProvider<HttpServiceClientProperties> properties, ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<HttpClientSettings> defaults, ObjectProvider<ClientHttpRequestFactoryBuilder<?>> factories,
            BeanFactory beanFactory) {
        String[] externals = topology.idsOf(Mode.EXTERNAL).toArray(String[]::new);
        ClassLoader loader = beanFactory instanceof ConfigurableBeanFactory c
                && c.getBeanClassLoader() != null ? c.getBeanClassLoader() : getClass().getClassLoader();
        return groups -> groups.filterByName(externals).forEachClient((group, builder) -> {
            checkOwnTypes(beanFactory.getBean(TopologyResolver.RESOLUTION_BEAN, TopologyResolver.Resolution.class),
                    group.name(), group.httpServiceTypes());
            endpoints.install(group.name(), builder, settings(group.name(), properties, sslBundles, defaults),
                    factories.getIfAvailable(() -> ClientHttpRequestFactoryBuilder.detect(loader)));
        });
    }

    /** Group {@code id}'s client settings as Boot's property configurer maps them, timeouts included. */
    static HttpClientSettings settings(String id, ObjectProvider<HttpServiceClientProperties> properties,
            ObjectProvider<SslBundles> sslBundles, ObjectProvider<HttpClientSettings> defaults) {
        HttpServiceClientProperties all = properties.getIfAvailable();
        HttpClientProperties own = all == null ? null : all.get(id);
        return new HttpClientSettingsPropertyMapper(sslBundles.getIfAvailable(),
                defaults.getIfAvailable(HttpClientSettings::defaults))
                .map(own == null ? new HttpClientProperties() : own);
    }

    /**
     * TL0003 when a host group is named like an external or loopback module and holds an interface that is not the
     * module's.
     */
    static void checkOwnTypes(TopologyResolver.Resolution resolution, String id, Set<Class<?>> types) {
        Map<String, String> mapped = resolution.httpInterfaces().getOrDefault(id, Map.of());
        Set<String> own = new HashSet<>();
        for (String contract : resolution.contracts().getOrDefault(id, Set.of())) {
            own.add(mapped.getOrDefault(contract, contract));
        }
        for (Class<?> type : types) {
            if (!own.contains(type.getName())) {
                String mode = resolution.topology().mode(id).id();
                throw new TopolithException(new Diagnostic(TopolithCode.TL0003, "HTTP service group '" + id
                        + "' holds " + type.getName() + ", which is not a contract of " + mode + " module '" + id
                        + "'.", "Rename the host's group: a group named like " + mode + " module '" + id
                        + "' is Topolith's."));
            }
        }
    }
}
