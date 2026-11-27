package io.github.rey5137.topolith.spring.boot.observation;

import io.github.rey5137.topolith.core.Incubating;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

/**
 * The convention used when the host has no {@link ModuleCallObservationConvention} bean (D-87): the name
 * {@code topolith.module.call}, the contextual name {@code <module.id> <contract simple name>.<method>} and the
 * low-cardinality keys {@code module.id}, {@code module.mode}, {@code caller.module}, {@code contract}, {@code method}
 * and {@code exception}, the same in every mode.
 */
@Incubating
public class DefaultModuleCallObservationConvention implements ModuleCallObservationConvention {
    /** The observation's name. */
    public static final String NAME = "topolith.module.call";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getContextualName(ModuleCallContext context) {
        return context.moduleId() + " " + context.contract().getSimpleName() + "." + context.method();
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ModuleCallContext context) {
        return KeyValues.of(KeyValue.of("module.id", context.moduleId()),
                KeyValue.of("module.mode", context.mode().id()),
                KeyValue.of("caller.module", context.callerModule()),
                KeyValue.of("contract", context.contract().getName()),
                KeyValue.of("method", context.method()),
                KeyValue.of("exception", context.exception()));
    }
}
