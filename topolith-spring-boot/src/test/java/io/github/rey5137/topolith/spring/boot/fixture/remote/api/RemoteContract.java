package io.github.rey5137.topolith.spring.boot.fixture.remote.api;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface RemoteContract {
    @GetExchange("/ping")
    String ping();
}
