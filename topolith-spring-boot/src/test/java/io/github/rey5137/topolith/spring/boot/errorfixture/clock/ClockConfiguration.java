package io.github.rey5137.topolith.spring.boot.errorfixture.clock;

import java.lang.reflect.Proxy;

import io.github.rey5137.topolith.spring.boot.errorfixture.clock.api.ClockContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.clockparts.ClockState;
import io.github.rey5137.topolith.spring.boot.errorfixture.clockparts.OutsideClock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The clock module's providers, picked by {@code test.clock.provider}: a lambda unless the test says otherwise. */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
    private static final String PROVIDER = "test.clock.provider";

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "lambda", matchIfMissing = true)
    ClockContract lambdaClock() {
        return ClockState::state;
    }

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "outside")
    ClockContract outsideClock() {
        return new OutsideClock();
    }

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "proxy")
    ClockContract proxyClock() {
        return (ClockContract) Proxy.newProxyInstance(ClockContract.class.getClassLoader(),
                new Class<?>[] {ClockContract.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "now" -> ClockState.state();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> "proxyClock";
                });
    }

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "two")
    ClockContract firstClock() {
        return ClockState::state;
    }

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "two")
    ClockContract secondClock() {
        return ClockState::state;
    }
}
