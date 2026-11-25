package io.github.rey5137.topolith.spring.boot.fixture.local.api;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface LocalContract {
    @GetExchange("/ping")
    String ping();
}
