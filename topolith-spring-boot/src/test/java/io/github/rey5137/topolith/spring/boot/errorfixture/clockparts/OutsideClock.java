package io.github.rey5137.topolith.spring.boot.errorfixture.clockparts;

import io.github.rey5137.topolith.spring.boot.errorfixture.clock.api.ClockContract;

/** A provider class outside every module package, which the clock module's {@code @Bean} method returns. */
public final class OutsideClock implements ClockContract {
    @Override
    public String now() {
        return ClockState.state();
    }
}
