package io.github.rey5137.topolith.spring.boot;

import java.time.Duration;

import java.util.Set;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/**
 * Loopback mode's callers (R-MODE-06, R-EXT-09): each loopback module's group client calls the host's own servlet
 * server ({@link LoopbackEndpoints}), and a delegating resolver hides the provider from callers outside the module
 * ({@link LoopbackCandidateResolver}, D-81). Startup fails with TL0026 when the application has no listening plain-HTTP
 * servlet server (D-76); a reactive application is left to {@link ReactiveExposureConfiguration}'s TL0028. Imported by
 * the topology selector beside {@link ExternalErrorsConfiguration}, only when a module is loopback and Boot's HTTP
 * client support is present (INV-2).
 */
@Configuration(proxyBeanMethods = false)
class LoopbackConfiguration {
    private static final String SERVLET_SERVER =
            "org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext";
    private static final String DISPATCHER_SERVLET_PATH =
            "org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath";

    /** TL0026: a loopback module needs a servlet web server that listens on a port, over plain HTTP (D-76). */
    static void checkServer(ApplicationContext context, Environment environment, Set<String> ids) {
        if (context instanceof ReactiveWebApplicationContext) {
            return;
        }
        String reason = null;
        String fix = null;
        if (!ClassUtils.isPresent(SERVLET_SERVER, LoopbackConfiguration.class.getClassLoader())
                || !Server.servlet(context)) {
            reason = "the application has no servlet web server (a non-web application, or a @SpringBootTest in the "
                    + "default MOCK web environment)";
            fix = "Run the application with a servlet web server; in a @SpringBootTest set webEnvironment = "
                    + "RANDOM_PORT.";
        } else if (Binder.get(environment).bind("server.port", Integer.class).orElse(0) == -1) {
            reason = "server.port is -1, so the server does not listen";
            fix = "Set server.port to a port, or to 0 for a random one.";
        } else if (Server.ssl(environment)) {
            reason = "the server uses SSL (server.ssl.*) and the loopback client calls plain HTTP";
            fix = "Turn SSL off with server.ssl.enabled=false, or set the module's mode to internal.";
        }
        if (reason != null) {
            throw new TopolithException(new Diagnostic(TopolithCode.TL0026, "loopback module(s) " + ids
                    + " have no usable client: " + reason + ".", fix));
        }
    }

    /**
     * Checks the server, then installs the candidate resolver. A post-processor runs before the context creates its
     * web server, so a server that SSL settings or a missing port would break never hides the TL0026.
     */
    @Bean
    static BeanFactoryPostProcessor topolithLoopbackCandidates() {
        return new Startup();
    }

    private static final class Startup implements BeanFactoryPostProcessor, ApplicationContextAware {
        private ApplicationContext context;

        @Override
        public void setApplicationContext(ApplicationContext context) {
            this.context = context;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
            Topology topology = factory.getBean(TopologyResolver.RESOLUTION_BEAN, TopologyResolver.Resolution.class)
                    .topology();
            checkServer(context, context.getEnvironment(), topology.idsOf(Mode.LOOPBACK));
            LoopbackCandidateResolver.install(factory);
        }
    }

    @Bean
    static LoopbackEndpoints topolithLoopbackEndpoints(Environment environment) {
        return new LoopbackEndpoints(environment);
    }

    /** Lowest order, as for external groups: it runs after Boot's property configurer and replaces its two parts. */
    @Bean
    RestClientHttpServiceGroupConfigurer topolithLoopbackClients(Topology topology, LoopbackEndpoints endpoints,
            ObjectProvider<HttpServiceClientProperties> properties, ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<HttpClientSettings> defaults, ObjectProvider<ClientHttpRequestFactoryBuilder<?>> factories,
            BeanFactory beanFactory, Environment environment) {
        Binder binder = Binder.get(environment);
        String[] loopbacks = topology.idsOf(Mode.LOOPBACK).toArray(String[]::new);
        ClassLoader loader = beanFactory instanceof ConfigurableBeanFactory c
                && c.getBeanClassLoader() != null ? c.getBeanClassLoader() : getClass().getClassLoader();
        return groups -> groups.filterByName(loopbacks).forEachClient((group, builder) -> {
            ExternalClientSwapConfiguration.checkOwnTypes(beanFactory.getBean(TopologyResolver.RESOLUTION_BEAN,
                    TopologyResolver.Resolution.class), group.name(), group.httpServiceTypes());
            HttpClientSettings settings = ExternalClientSwapConfiguration.settings(group.name(), properties,
                    sslBundles, defaults);
            endpoints.install(group.name(), builder, factories.getIfAvailable(
                    () -> ClientHttpRequestFactoryBuilder.detect(loader)).build(settings),
                    LoopbackFaults.read(binder, group.name()), settings.readTimeout() == null
                            ? LoopbackFaults.DEFAULT_READ_TIMEOUT : settings.readTimeout());
        });
    }

    /** Reads the main server's local port once it has started; a management server's event names another context. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = SERVLET_SERVER)
    static class ServerPort {
        @Bean
        ApplicationListener<WebServerInitializedEvent> topolithLoopbackPort(ApplicationContext context,
                LoopbackEndpoints endpoints) {
            return new ApplicationListener<>() {
                @Override
                public void onApplicationEvent(WebServerInitializedEvent event) {
                    if (event.getApplicationContext() == context) {
                        endpoints.listen(event.getWebServer().getPort(), servletPath(context));
                    }
                }
            };
        }
    }

    /**
     * Boot's resolved {@code DispatcherServletPath} prefix ({@code spring.mvc.servlet.path}), or empty when Boot's
     * Spring MVC auto-configuration is absent: then Boot registers no dispatcher servlet path.
     */
    static String servletPath(ApplicationContext context) {
        return ClassUtils.isPresent(DISPATCHER_SERVLET_PATH, LoopbackConfiguration.class.getClassLoader())
                ? Mvc.prefix(context) : "";
    }

    /** Boot's Spring MVC types, loaded only once {@link #DISPATCHER_SERVLET_PATH} is known to be present. */
    private static final class Mvc {
        static String prefix(ApplicationContext context) {
            DispatcherServletPath path = context.getBeanProvider(DispatcherServletPath.class).getIfAvailable();
            return path == null ? "" : path.getPrefix();
        }
    }

    /** The web-server types, loaded only once {@link #SERVLET_SERVER} is known to be present. */
    private static final class Server {
        static boolean servlet(ApplicationContext context) {
            return context instanceof ServletWebServerApplicationContext;
        }

        static boolean ssl(Environment environment) {
            return Ssl.isEnabled(Binder.get(environment).bind("server.ssl", Ssl.class).orElse(null));
        }
    }
}
