package io.github.rey5137.topolith.spring.boot.errorfixture.valid.api;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface ValidCheckedContract {
    @GetExchange("/read")
    String read() throws ApiCheckedException;
}
