package io.github.rey5137.topolith.spring.cloud.fixture.user.api;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface UserContract {
    @GetExchange("/ping")
    String ping();
}
