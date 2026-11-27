package io.github.rey5137.topolith.spring.boot.errorfixture.listed.api;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

public interface ListedContract {
    @GetExchange("/value")
    String value(@RequestParam("input") String input) throws ListedFailure;
}
