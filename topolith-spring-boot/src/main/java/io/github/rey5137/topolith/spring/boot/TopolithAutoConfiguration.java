package io.github.rey5137.topolith.spring.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

/**
 * Activates the internal modules (D-30). It is ordered first and imports a non-deferred selector, so a module's beans
 * are parsed before the conditions of every later auto-configuration are evaluated.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@Import(TopolithModuleImportSelector.class)
public class TopolithAutoConfiguration {
}
