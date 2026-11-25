package io.github.rey5137.topolith.spring.boot.errorfixture.separate;

import io.github.rey5137.topolith.spring.boot.errorfixture.separate.api.PlainContract;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface PlainHttp extends PlainContract {
    @Override
    @GetExchange("/find")
    String find(@RequestParam("value") String value);
}
