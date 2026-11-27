package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * The contracts a module declaration lists in {@code provides} (D-110), once every definition is registered: an
 * internal or loopback module contributes exactly one bean of each, owned by its definition as D-61 assigns it, so
 * several fail with TL0005 and none with TL0044. A {@code none} or {@code external} module has no entry.
 */
final class ListedContractsCheck implements BeanFactoryPostProcessor, EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) throws BeansException {
        ClassLoader loader = factory.getBeanClassLoader();
        TopologyResolver.Resolution resolution = TopologyResolver.resolve(factory, environment, loader);
        List<Diagnostic> found = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : resolution.listed().entrySet()) {
            String id = entry.getKey();
            for (String contract : entry.getValue()) {
                List<String> beans = new ArrayList<>();
                ModuleBeans.ownedBy(factory, loader, resolution, contract).forEach((name, owner) -> {
                    if (id.equals(owner)) {
                        beans.add(name);
                    }
                });
                if (beans.isEmpty()) {
                    found.add(new Diagnostic(TopolithCode.TL0044, "module '" + id + "' lists " + contract
                            + " in provides, but none of its definitions is a bean of it.", "Declare one @Bean of "
                                    + contract + " in a configuration under the module's package, or remove it "
                                    + "from provides."));
                } else if (beans.size() > 1) {
                    found.add(new Diagnostic(TopolithCode.TL0005, "module '" + id + "' contributes " + beans.size()
                            + " beans of the listed contract " + contract + ": " + beans + ".",
                            "Keep exactly one bean of " + contract + " in the module."));
                }
            }

        }
        if (!found.isEmpty()) {
            throw new TopolithException(found);
        }
    }
}
