package io.github.rey5137.topolith.spring.boot.observation;

import io.github.rey5137.topolith.core.Incubating;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;

/**
 * The naming and keys of the {@code topolith.module.call} observation. One bean of this type replaces
 * {@link DefaultModuleCallObservationConvention}; two fail startup with TL0033 (D-89).
 */
@Incubating
public interface ModuleCallObservationConvention extends ObservationConvention<ModuleCallContext> {
    @Override
    default boolean supportsContext(Observation.Context context) {
        return context instanceof ModuleCallContext;
    }
}
