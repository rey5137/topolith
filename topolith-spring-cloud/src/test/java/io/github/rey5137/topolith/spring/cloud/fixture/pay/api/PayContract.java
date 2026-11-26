package io.github.rey5137.topolith.spring.cloud.fixture.pay.api;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface PayContract {
    @GetExchange("/ping")
    String ping();
}
