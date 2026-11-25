package io.github.rey5137.topolith.spring.boot.fixture.remote.api;

import org.springframework.web.service.annotation.GetExchange;

public interface MethodContract {
    @GetExchange("/method")
    String method();
}
