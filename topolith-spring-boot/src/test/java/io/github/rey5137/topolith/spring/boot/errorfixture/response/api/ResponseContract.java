package io.github.rey5137.topolith.spring.boot.errorfixture.response.api;

import java.util.concurrent.CompletableFuture;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.service.annotation.GetExchange;

public interface ResponseContract {
    @GetExchange("/entity")
    ResponseEntity<String> entity();

    @GetExchange("/headers")
    HttpHeaders headers();

    @GetExchange("/future")
    CompletableFuture<String> future();

    @GetExchange("/deferred")
    DeferredResult<String> deferred();
}
