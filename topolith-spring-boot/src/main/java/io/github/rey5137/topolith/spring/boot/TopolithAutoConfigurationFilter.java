package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.Topology;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Removes the auto-configurations owned by a non-internal module (R-MODE-02, INV-2): a class under the module's
 * package, or one listed by an {@code AutoConfiguration.imports} file in the root of the module's own artifact.
 * Classes owned by no module are left alone. It reads names only and loads no class. Registered in
 * {@code META-INF/spring.factories}.
 */
public class TopolithAutoConfigurationFilter
        implements AutoConfigurationImportFilter, BeanFactoryAware, EnvironmentAware, BeanClassLoaderAware {
    private BeanFactory beanFactory;
    private Environment environment;
    private ClassLoader classLoader;

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
        TopologyResolver.Resolution resolution = TopologyResolver.resolve(beanFactory, environment, classLoader);
        Topology topology = resolution.topology();
        boolean[] keep = new boolean[autoConfigurationClasses.length];
        for (int i = 0; i < keep.length; i++) {
            String name = autoConfigurationClasses[i];
            String owner = topology.ownerOfClass(name).orElse(resolution.importOwners().get(name));
            keep[i] = owner == null || topology.mode(owner) == Mode.INTERNAL;
        }
        return keep;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }
}
