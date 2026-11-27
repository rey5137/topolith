package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Route coverage (D-126): once every singleton exists and before the server starts, each abstract method of each
 * contract an exposed or loopback module provides must be served by a module route, a handler method of a bean the
 * module owns that implements that contract method. An uncovered method, such as one of an inferred contract
 * implemented only by a non-handler {@code @Service}, fails with TL0044; otherwise the caller would find out only at
 * call time, as a {@code status 404}. An unexposed internal module and a {@code none} or {@code external} module are
 * not checked.
 */
final class RouteCoverageCheck implements SmartInitializingSingleton {
    private final ListableBeanFactory beanFactory;

    RouteCoverageCheck(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        TopologyResolver.Resolution resolution = beanFactory.getBean(TopologyResolver.RESOLUTION_BEAN,
                TopologyResolver.Resolution.class);
        if (resolution.exposed().isEmpty()) {
            return;
        }
        List<HandlerMethod> handlers = new ArrayList<>();
        beanFactory.getBeansOfType(RequestMappingHandlerMapping.class, false, false).values()
                .forEach(mapping -> handlers.addAll(mapping.getHandlerMethods().values()));
        List<Diagnostic> found = new ArrayList<>();
        for (String id : resolution.exposed()) {
            ClassLoader loader = beanFactory instanceof ConfigurableListableBeanFactory configurable
                    ? configurable.getBeanClassLoader() : ClassUtils.getDefaultClassLoader();
            List<HandlerMethod> routes = handlers.stream().filter(handler -> owned(handler, id, resolution)).toList();
            for (String name : resolution.contracts().getOrDefault(id, Set.of())) {
                Class<?> contract = ModuleBeans.load(name, loader);
                if (contract == null || !contract.isInterface()) {
                    // an unloadable contract is reported where it is loaded (TL0026)
                    continue;
                }
                Set<String> uncovered = new TreeSet<>();
                for (Method method : contract.getMethods()) {
                    // a redeclared Object method, such as toString, is no contract method: Object implements it
                    if (Modifier.isAbstract(method.getModifiers()) && !ReflectionUtils.isObjectMethod(method)
                            && !served(method, contract, routes)) {
                        uncovered.add(signature(method));
                    }
                }
                if (!uncovered.isEmpty()) {
                    String why = resolution.topology().mode(id) == Mode.LOOPBACK ? "loopback"
                            : "exposed (topolith.modules." + id + ".expose.enabled=true)";
                    found.add(new Diagnostic(TopolithCode.TL0044, "module '" + id + "' is " + why + ", but no "
                            + "module route serves " + name + " method(s) " + uncovered + ".", "Implement " + name
                                    + " in a module @RestController (D-53), through a separate HTTP interface (D-49) "
                                    + "if the contract carries no @HttpExchange; a non-handler bean such as a "
                                    + "@Service has no route."));
                }
            }
        }
        if (!found.isEmpty()) {
            throw new TopolithException(found);
        }
    }

    /**
     * Whether module {@code id} owns the handler: by its bean definition (D-61), and by its class, which is what maps
     * the route under {@code /<id>} (D-44).
     */
    private boolean owned(HandlerMethod handler, String id, TopologyResolver.Resolution resolution) {
        if (!id.equals(ModuleHandlers.internalOwner(resolution.topology(), handler.getBeanType()))) {
            return false;
        }
        if (handler.getBean() instanceof String name && beanFactory instanceof ConfigurableListableBeanFactory factory
                && factory.containsBeanDefinition(name)) {
            return ModuleBeans.owner(factory.getBeanDefinition(name), resolution).map(id::equals).orElse(true);
        }
        return true;
    }

    /** The method's name and simple parameter types, so the message tells overloads apart. */
    private static String signature(Method method) {
        StringJoiner parameters = new StringJoiner(", ", method.getName() + "(", ")");
        for (Class<?> type : method.getParameterTypes()) {
            parameters.add(type.getSimpleName());
        }
        return parameters.toString();
    }

    /** Whether a route's handler method is the implementation of the contract method in its handler class. */
    private static boolean served(Method method, Class<?> contract, Collection<HandlerMethod> routes) {
        for (HandlerMethod route : routes) {
            Class<?> type = ClassUtils.getUserClass(route.getBeanType());
            if (contract.isAssignableFrom(type) && BridgeMethodResolver.findBridgedMethod(ClassUtils
                    .getMostSpecificMethod(method, type)).equals(BridgeMethodResolver.findBridgedMethod(
                            route.getMethod()))) {
                return true;
            }
        }
        return false;
    }
}
