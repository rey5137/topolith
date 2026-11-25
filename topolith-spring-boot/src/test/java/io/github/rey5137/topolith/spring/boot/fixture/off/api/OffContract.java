package io.github.rey5137.topolith.spring.boot.fixture.off.api;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface OffContract {
    @GetExchange("/ping")
    String ping();
}
