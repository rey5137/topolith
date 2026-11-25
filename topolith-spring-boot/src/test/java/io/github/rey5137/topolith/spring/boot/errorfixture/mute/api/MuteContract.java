package io.github.rey5137.topolith.spring.boot.errorfixture.mute.api;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface MuteContract {
    @GetExchange("/ping")
    String ping();
}
