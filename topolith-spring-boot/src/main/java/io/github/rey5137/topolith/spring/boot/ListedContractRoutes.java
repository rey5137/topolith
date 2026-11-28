package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Registers the routes of listed contracts and records their definition-based ownership (D-125). */
final class ListedContractRoutes {
    private static final Pattern VARIABLE = Pattern.compile("\\{(\\*?)[\\w.-]+(?=[:}])");

    private final Map<HandlerMethod, String> owners = new HashMap<>();

    String owner(HandlerMethod handler) {
        String owner = owners.get(handler);
        HandlerMethod original = handler.getResolvedFromHandlerMethod();
        return owner != null || original == null ? owner : owners.get(original);
    }

    String internalOwner(Topology topology, HandlerMethod handler) {
        String registered = owner(handler);
        return registered != null ? registered : ModuleHandlers.internalOwner(topology, handler.getBeanType());
    }

    boolean contains(Method method) {
        return owners.keySet().stream().anyMatch(handler -> handler.getMethod().equals(method));
    }

    void register(ConfigurableListableBeanFactory factory, TopologyResolver.Resolution resolution,
            RequestMappingHandlerMapping host) {
        ClassLoader loader = factory.getBeanClassLoader();
        Map<String, HandlerMethod> known = new HashMap<>();
        host.getHandlerMethods().forEach((info, handler) -> known.putIfAbsent(normalized(info), handler));
        for (String id : resolution.exposed()) {
            for (String name : resolution.listed().getOrDefault(id, Set.of())) {
                Class<?> contract = ModuleBeans.load(name, loader);
                if (contract == null || !contract.isInterface()) {
                    continue;
                }
                String bean = ModuleBeans.beansOf(factory, loader, resolution, name, id).stream().findFirst().orElseThrow();
                ContractMapping mapping = new ContractMapping(host, factory, id);
                for (Method method : contract.getMethods()) {
                    if (!Modifier.isAbstract(method.getModifiers()) || ReflectionUtils.isObjectMethod(method)) {
                        continue;
                    }
                    if (!MergedAnnotations.from(method).isPresent(HttpExchange.class)) {
                        throw new TopolithException(new Diagnostic(TopolithCode.TL0026,
                                "listed contract method " + method + " has no @HttpExchange.",
                                "Annotate the method with an @HttpExchange-family annotation."));
                    }
                    RequestMappingInfo info = mapping.forMethod(method, contract);
                    HandlerMethod existing = known.get(normalized(info));
                    if (existing != null) {
                        throw new TopolithException(new Diagnostic(TopolithCode.TL0048,
                                "route " + info + " for " + method + " clashes with " + existing + ".",
                                "Give the handlers distinct mappings."));
                    }
                    host.registerMapping(info, bean, method);
                    HandlerMethod registered = host.getHandlerMethods().get(info);
                    known.put(normalized(info), registered);
                    owners.put(registered, id);
                }
            }
        }
    }

    /**
     * The mapping as text with every path-variable name dropped, so {@code /{id}} and {@code /{key}} compare equal
     * (D-136). The text carries the methods, patterns, params, headers, consumes, produces and custom conditions, so two
     * mappings are the same only when all of them are.
     */
    static String normalized(RequestMappingInfo info) {
        return VARIABLE.matcher(info.toString()).replaceAll("{$1");
    }

    private static final class ContractMapping extends RequestMappingHandlerMapping {
        ContractMapping(RequestMappingHandlerMapping host, ConfigurableListableBeanFactory factory, String id) {
            setPatternParser(host.getPatternParser());
            setContentNegotiationManager(host.getContentNegotiationManager());
            setApiVersionStrategy(host.getApiVersionStrategy());
            setEmbeddedValueResolver(factory::resolveEmbeddedValue);
            setPathPrefixes(Map.of("/" + id, type -> true));
            afterPropertiesSet();
        }

        @Override
        protected void initHandlerMethods() {
        }

        RequestMappingInfo forMethod(Method method, Class<?> contract) {
            return getMappingForMethod(method, contract);
        }
    }
}
