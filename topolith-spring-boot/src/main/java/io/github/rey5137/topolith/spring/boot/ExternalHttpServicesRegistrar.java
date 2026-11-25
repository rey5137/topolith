package io.github.rey5137.topolith.spring.boot;

import java.util.Set;
import java.util.Map;

import io.github.rey5137.topolith.core.Mode;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.web.service.registry.AbstractHttpServiceRegistrar;
import org.springframework.web.service.registry.HttpServiceGroup.ClientType;

/**
 * Registers one HTTP service group per external module, named for the module id, holding the contract interfaces
 * from its descriptors' {@code provides} that carry {@code @HttpExchange} (R-EXT-01). The group's client settings come
 * from {@code spring.http.serviceclient.<id>.*} (R-EXT-02, R-EXT-03). Only imported when {@code spring-web} is present.
 */
final class ExternalHttpServicesRegistrar extends AbstractHttpServiceRegistrar {
    private BeanFactory beanFactory;
    private Environment environment;
    private ClassLoader classLoader;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        super.setBeanFactory(beanFactory);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
        super.setEnvironment(environment);
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
        super.setBeanClassLoader(classLoader);
    }

    @Override
    protected void registerHttpServices(GroupRegistry registry, AnnotationMetadata importingClassMetadata) {
        TopologyResolver.Resolution resolution = TopologyResolver.resolve(beanFactory, environment, classLoader);
        for (String id : resolution.topology().idsOf(Mode.EXTERNAL)) {
            Class<?>[] contracts = resolution.contracts().getOrDefault(id, Set.of()).stream()
                    .map(name -> load(resolution.httpInterfaces().getOrDefault(id, Map.of()).getOrDefault(name, name)))
                    .toArray(Class<?>[]::new);
            if (contracts.length > 0) {
                registry.forGroup(id, ClientType.REST_CLIENT).register(contracts);
            }
        }
    }

    /** The resolver already checked (TL0026) that every contract of an external module loads. */
    private Class<?> load(String name) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
