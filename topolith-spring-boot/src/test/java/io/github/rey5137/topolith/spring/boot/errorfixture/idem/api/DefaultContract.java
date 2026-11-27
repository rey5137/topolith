package io.github.rey5137.topolith.spring.boot.errorfixture.idem.api;

import io.github.rey5137.topolith.contract.Idempotent;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** {@code @Idempotent} on a default method, which runs on the proxy and is never an exchange (TL0040). */
@HttpExchange("/default")
public interface DefaultContract {
    @GetExchange("/find")
    String find();

    @Idempotent
    default String first() {
        return find();
    }
}
