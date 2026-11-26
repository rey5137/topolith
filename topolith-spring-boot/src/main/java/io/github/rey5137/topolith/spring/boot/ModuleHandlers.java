package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Topology;
import org.springframework.util.ClassUtils;

/**
 * Which web handlers belong to a module: those whose bean type lies in the package of a module that is internal for
 * activation, an internal or loopback one (D-30, D-75).
 */
final class ModuleHandlers {
    private ModuleHandlers() {
    }

    /**
     * The id of the internal or loopback module whose package, subpackages included, holds {@code beanType}; null for
     * a host handler or a class of a module that is neither. A separate HTTP interface's controller counts, since only
     * the package decides.
     */
    static String internalOwner(Topology topology, Class<?> beanType) {
        String owner = topology.ownerOfClass(ClassUtils.getUserClass(beanType).getName()).orElse(null);
        return owner != null && topology.internal().contains(owner) ? owner : null;
    }
}
