package io.github.rey5137.topolith.spring.boot.errorfixture.checked.api;

import java.io.IOException;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange("/checked")
public interface CheckedContract {
    @GetExchange("/read")
    String read() throws IOException;

    @GetExchange("/size")
    int size();
}
