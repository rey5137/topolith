package io.github.rey5137.topolith.spring.boot.errorfixture.cover.api;

import org.springframework.web.service.annotation.GetExchange;

/** A superinterface whose method a contract inherits. */
public interface Pinger {
    @GetExchange("/good/ping")
    String ping();
}
