package io.github.rey5137.topolith.spring.boot.errorfixture.status.api;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface StatusContract {
    @GetExchange("/ping")
    String ping();
}
