package io.github.rey5137.topolith.spring.boot.errorfixture.response;

import java.util.concurrent.CompletableFuture;

import io.github.rey5137.topolith.spring.boot.errorfixture.response.api.ResponseContract;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

@Configuration(proxyBeanMethods = false)
class ResponseConfiguration {
    @Bean
    ResponseContract responseProvider() {
        return new ResponseContract() {
            public ResponseEntity<String> entity() {
                return ResponseController.answer();
            }

            public HttpHeaders headers() {
                return ResponseController.headersAnswer();
            }

            public CompletableFuture<String> future() {
                return CompletableFuture.completedFuture("future body");
            }

            public DeferredResult<String> deferred() {
                DeferredResult<String> result = new DeferredResult<>();
                result.setResult("deferred body");
                return result;
            }
        };
    }
}
