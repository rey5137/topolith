package io.github.rey5137.topolith.spring.boot.errorfixture.other.api;

import io.github.rey5137.topolith.spring.boot.errorfixture.listed.api.ListedContract;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

public interface OtherContract extends ListedContract {
    @Override
    @GetExchange("/value")
    String value(@RequestParam("input") String input);
}
