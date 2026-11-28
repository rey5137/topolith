package io.github.rey5137.topolith.spring.boot.internal;

import java.util.Optional;
import java.util.Set;

import io.github.rey5137.topolith.core.Incubating;
import org.springframework.beans.factory.config.BeanDefinition;

/** Which module owns a bean by its definition, and its listed contracts, shared with the test helper (D-110, D-128). */
@Incubating
public interface ListedContracts {
    /** The module that contributed the definition (D-61), whatever its mode. */
    Optional<String> definitionOwner(BeanDefinition definition);

    /** The contracts the module's {@code @TopolithModule(provides = ...)} lists (D-128); empty when none. */
    Set<String> listed(String module);
}
