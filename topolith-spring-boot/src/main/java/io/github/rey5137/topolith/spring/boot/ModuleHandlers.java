package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.util.ClassUtils;

/** Which web handlers belong to a module: those whose bean type lies in an internal module's package (D-30). */
final class ModuleHandlers {
    private ModuleHandlers() {
    }

    /**
     * The id of the internal module whose package, subpackages included, holds {@code beanType}; null for a host
     * handler or a class of a module that is not internal. A separate HTTP interface's controller counts, since only
     * the package decides.
     */
    static String internalOwner(Topology topology, Class<?> beanType) {
        String owner = topology.ownerOfClass(ClassUtils.getUserClass(beanType).getName()).orElse(null);
        return owner != null && topology.mode(owner) == Mode.INTERNAL ? owner : null;
    }
}
