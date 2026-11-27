package io.github.rey5137.topolith.spring.boot.errorfixture.idem.api;

import io.github.rey5137.topolith.contract.Idempotent;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** Two overloads of {@code find} that disagree on {@code @Idempotent} (TL0040). */
@HttpExchange("/overload")
public interface OverloadContract {
    @Idempotent
    @GetExchange("/all")
    String find();

    @GetExchange("/one")
    String find(@RequestParam("id") String id);
}
