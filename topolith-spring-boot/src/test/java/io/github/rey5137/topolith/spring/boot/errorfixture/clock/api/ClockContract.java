package io.github.rey5137.topolith.spring.boot.errorfixture.clock.api;

import org.springframework.web.service.annotation.GetExchange;

/** A contract no class implements: a {@code @Bean} lambda or factory result provides it (D-110). */
public interface ClockContract {
    @GetExchange("/now")
    String now();
}
