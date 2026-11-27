package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/** Factory for host customizations of external and loopback module clients. */
@Incubating
public final class ModuleClients {
    private ModuleClients() {
    }

    /**
     * Customize only the named external or loopback module's client. The Topolith base URL and request factory win:
     * attempts to replace them are rejected. Replacing the URI factory, default URI variables, interceptor list or message converters and cloning the
     * builder are also rejected to preserve those settings. A host status handler throwing its own exception bypasses Topolith's error
     * translation.
     * Declare the returned configurer as a Spring bean.
     */
    public static RestClientHttpServiceGroupConfigurer configure(String moduleId,
            Consumer<RestClient.Builder> customizer) {
        return new ModuleClientConfigurer(Objects.requireNonNull(moduleId), Objects.requireNonNull(customizer));
    }

    /**
     * Make {@code interceptor} the named external or loopback module's only outgoing-auth hook, instead of the one
     * {@code topolith.modules.<id>.auth.type} chooses. It runs after every interceptor a {@link #configure}
     * customizer adds; a second hook for the module, or an explicit {@code auth.type}, fails startup with TL0036, and
     * a request whose {@code Authorization}, set before the hook, the hook changes fails with TL0036 without being
     * sent. Declare the returned configurer as a Spring bean.
     */
    public static RestClientHttpServiceGroupConfigurer authenticate(String moduleId,
            ClientHttpRequestInterceptor interceptor) {
        return new ModuleAuthConfigurer(Objects.requireNonNull(moduleId), Objects.requireNonNull(interceptor));
    }

    /** TL0003 unless {@code moduleId} is an external or loopback module; {@code what} names the host's setting. */
    static void requireClientModule(BeanFactory beanFactory, String moduleId, String what) {
        Topology topology = beanFactory.getBean(Topology.class);
        if (!topology.known().contains(moduleId)
                || topology.mode(moduleId) != Mode.EXTERNAL && topology.mode(moduleId) != Mode.LOOPBACK) {
            String property = "topolith.modules." + moduleId + ".mode";
            throw new TopolithException(new Diagnostic(TopolithCode.TL0003, what + " for module '"
                    + moduleId + "' requires " + property + "=external or loopback.", "Set " + property
                    + " to external or loopback."));
        }
    }
}

/** A host's outgoing-auth hook; {@link OutgoingAuth} installs it, so the configurer itself changes nothing. */
final class ModuleAuthConfigurer implements RestClientHttpServiceGroupConfigurer, BeanFactoryAware, InitializingBean {
    private final String moduleId;
    private final ClientHttpRequestInterceptor interceptor;
    private BeanFactory beanFactory;

    ModuleAuthConfigurer(String moduleId, ClientHttpRequestInterceptor interceptor) {
        this.moduleId = moduleId;
        this.interceptor = interceptor;
    }

    String moduleId() {
        return moduleId;
    }

    ClientHttpRequestInterceptor interceptor() {
        return interceptor;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterPropertiesSet() {
        ModuleClients.requireClientModule(beanFactory, moduleId, "an outgoing-auth hook");
    }

    @Override
    public void configureGroups(Groups<RestClient.Builder> groups) {
    }
}

final class ModuleClientConfigurer implements RestClientHttpServiceGroupConfigurer, BeanFactoryAware, InitializingBean {
    private static final Set<String> PROTECTED = Set.of("baseUrl", "uriBuilderFactory", "requestFactory",
            "requestInterceptors", "defaultUriVariables", "messageConverters", "configureMessageConverters", "clone");
    private final String moduleId;
    private final Consumer<RestClient.Builder> customizer;
    private BeanFactory beanFactory;

    ModuleClientConfigurer(String moduleId, Consumer<RestClient.Builder> customizer) {
        this.moduleId = moduleId;
        this.customizer = customizer;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterPropertiesSet() {
        ModuleClients.requireClientModule(beanFactory, moduleId, "client customization");
    }

    @Override
    public void configureGroups(Groups<RestClient.Builder> groups) {
        groups.filterByName(moduleId).forEachClient((group, builder) -> customizer.accept(guard(builder)));
    }

    private static RestClient.Builder guard(RestClient.Builder builder) {
        return (RestClient.Builder) Proxy.newProxyInstance(RestClient.Builder.class.getClassLoader(),
                new Class<?>[] {RestClient.Builder.class}, (proxy, method, args) -> {
                    if (PROTECTED.contains(method.getName())) {
                        throw new IllegalArgumentException("Topolith owns client setting " + method.getName());
                    }
                    if (method.getName().equals("requestInterceptor")) {
                        // before the module's outgoing-auth hook, whichever configurer runs first (D-92)
                        ClientHttpRequestInterceptor added = (ClientHttpRequestInterceptor) args[0];
                        builder.requestInterceptors(list -> AuthHook.insert(list, added));
                        return proxy;
                    }
                    if (method.getName().equals("apply")) {
                        @SuppressWarnings("unchecked")
                        Consumer<RestClient.Builder> nested = (Consumer<RestClient.Builder>) args[0];
                        nested.accept((RestClient.Builder) proxy);
                        return proxy;
                    }
                    try {
                        Object result = method.invoke(builder, args);
                        return result == builder ? proxy : result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
