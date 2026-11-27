package io.github.rey5137.topolith.spring.boot.internal;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.rey5137.topolith.core.Incubating;

/**
 * What {@code topolith-spring-cloud} needs of each external and loopback module to wire retry and the circuit breaker:
 * its settings, its descriptor's {@code idempotent} set and its separate HTTP interfaces (R-EXT-14, R-EXT-15). Only
 * those modules are in it, so a {@code none} or {@code internal} module leaves nothing behind (INV-2). Not public API
 * (D-109).
 */
@Incubating
public final class ResilienceModules {
    /**
     * One module's inputs; {@code httpInterfaces} maps a contract's name to its D-49 interface and {@code services} is
     * the names of the interfaces its group proxies.
     */
    public record Module(ResilienceSettings.Retry retry, ResilienceSettings.Breaker breaker, Set<String> idempotent,
            Map<String, String> httpInterfaces, Set<String> services) {
    }

    private final Map<String, Module> modules;

    public ResilienceModules(Map<String, Module> modules) {
        this.modules = new TreeMap<>(modules);
    }

    /** The ids of the external and loopback modules. */
    public Set<String> ids() {
        return modules.keySet();
    }

    public Module module(String id) {
        return modules.get(id);
    }

    /**
     * Whether module {@code id}'s descriptor lists the contract method as idempotent (D-100). {@code service} is the
     * interface the client proxies: the contract, or the separate interface mapped to it.
     */
    public boolean declaredIdempotent(String id, Class<?> service, Method method) {
        Module module = modules.get(id);
        if (module == null) {
            return false;
        }
        String contract = service.getName();
        for (Map.Entry<String, String> mapped : module.httpInterfaces().entrySet()) {
            if (mapped.getValue().equals(service.getName())) {
                contract = mapped.getKey();
            }
        }
        return module.idempotent().contains(contract + "#" + method.getName());
    }
}
