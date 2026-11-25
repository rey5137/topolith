package io.github.rey5137.topolith.spring.boot;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/** Counts into the host's {@link MeterRegistry}, when it has one; loaded only when Micrometer is present. */
final class MicrometerCallCounter implements ContractTransactionBoundary.CallCounter {
    private final ObjectProvider<MeterRegistry> registry;

    MicrometerCallCounter(BeanFactory beanFactory) {
        this.registry = beanFactory.getBeanProvider(MeterRegistry.class);
    }

    @Override
    public void count(String module, String contract) {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters != null) {
            meters.counter(ContractTransactionBoundary.METRIC, "module", module, "contract", contract).increment();
        }
    }
}
