package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.beans.factory.BeanFactory;
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
        ClassLoader loader = beanFactory instanceof org.springframework.beans.factory.config.ConfigurableBeanFactory c
                && c.getBeanClassLoader() != null ? c.getBeanClassLoader() : getClass().getClassLoader();
        return groups -> groups.filterByName(externals).forEachClient((group, builder) -> {
            HttpServiceClientProperties all = properties.getIfAvailable();
            HttpClientProperties own = all == null ? null : all.get(group.name());
            HttpClientSettings settings = new HttpClientSettingsPropertyMapper(sslBundles.getIfAvailable(),
                    defaults.getIfAvailable(HttpClientSettings::defaults))
                    .map(own == null ? new HttpClientProperties() : own);
            endpoints.install(group.name(), builder, settings,
                    factories.getIfAvailable(() -> ClientHttpRequestFactoryBuilder.detect(loader)));
        });
    }
}
