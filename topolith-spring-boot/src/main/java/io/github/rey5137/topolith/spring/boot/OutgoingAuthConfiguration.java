package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/**
 * Installs each external and loopback module's outgoing-auth hook in its client (R-EXT-13). Imported beside
 * {@link ExternalErrorsConfiguration}, only when a module is external or loopback, so an internal or {@code none}
 * module gets no hook (INV-2).
 */
@Configuration(proxyBeanMethods = false)
class OutgoingAuthConfiguration {
    @Bean
    static OutgoingAuth topolithOutgoingAuth(Topology topology, Environment environment, ListableBeanFactory beans,
            ObjectProvider<OutgoingAuth.TokenRequests> tokenRequests) {
        return new OutgoingAuth(topology, Binder.get(environment), beans, loader(beans),
                tokenRequests.getIfAvailable());
    }

    private static ClassLoader loader(BeanFactory beans) {
        return beans instanceof ConfigurableBeanFactory configurable && configurable.getBeanClassLoader() != null
                ? configurable.getBeanClassLoader() : ClassUtils.getDefaultClassLoader();
    }

    /** The hooks are resolved at startup even when no client is built: TL0036 and TL0037 fail it (D-92, D-94). */
    @Bean
    @Lazy(false)
    static SmartInitializingSingleton topolithOutgoingAuthCheck(OutgoingAuth auth) {
        return auth::hooks;
    }

    /**
     * Runs after every other configurer, so the default headers it inspects are final and no host configurer
     * appends an interceptor behind the hook (R-EXT-13). Beans of equal order keep registration order and an
     * auto-configuration registers after the host's own beans.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    RestClientHttpServiceGroupConfigurer topolithOutgoingAuthHooks(Topology topology, OutgoingAuth auth) {
        String[] callers = ExternalHttpServicesRegistrar.clientModules(topology).toArray(String[]::new);
        return groups -> groups.filterByName(callers).forEachClient((group, builder) -> {
            ClientHttpRequestInterceptor hook = auth.hooks().get(group.name());
            if (hook != null) {
                // a default Authorization would collide with the hook whenever a caller holds a token (D-92)
                builder.defaultHeaders(headers -> {
                    if (headers.containsHeader(HttpHeaders.AUTHORIZATION)) {
                        throw new TopolithException(new Diagnostic(TopolithCode.TL0036, "a client customizer of "
                                + "module '" + group.name() + "' sets a default Authorization header and the "
                                + "module has an outgoing-auth hook.",
                                "Set credentials in either the customizer or the hook (auth.type), not both."));
                    }
                });
                builder.requestInterceptor(new AuthHook(group.name(), hook));
            }
        });
    }

    /**
     * A client-credentials token request gets its module's connect and read timeouts, nothing else (D-96). Boot's
     * HTTP client support is always there when a module has a client (TL0026); the guard keeps the class optional.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.http.client.HttpClientSettings")
    static class TokenRequestTimeouts {
        @Bean
        static OutgoingAuth.TokenRequests topolithTokenRequests(ObjectProvider<HttpServiceClientProperties> properties,
                ObjectProvider<SslBundles> sslBundles, ObjectProvider<HttpClientSettings> defaults,
                ObjectProvider<ClientHttpRequestFactoryBuilder<?>> factories, BeanFactory beans) {
            return new OutgoingAuth.TokenRequests(id -> {
                HttpClientSettings module = ExternalClientSwapConfiguration.settings(id, properties, sslBundles,
                        defaults);
                // never the module's SSL bundle or redirects: the token endpoint is the registration's server
                return factories.getIfAvailable(() -> ClientHttpRequestFactoryBuilder.detect(loader(beans)))
                        .build(HttpClientSettings.defaults().withTimeouts(module.connectTimeout(),
                                module.readTimeout()));
            });
        }
    }
}
