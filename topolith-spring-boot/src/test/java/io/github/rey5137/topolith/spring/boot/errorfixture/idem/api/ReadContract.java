package io.github.rey5137.topolith.spring.boot.errorfixture.idem.api;

import io.github.rey5137.topolith.contract.Idempotent;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** Idempotent are {@code get} and the inherited {@code count}; {@code save} and the default method are not. */
@HttpExchange("/read")
public interface ReadContract extends Lookup {
    @Idempotent
    @GetExchange("/get")
    String get(@RequestParam("id") String id);

    @PostExchange("/save")
    String save(@RequestBody String value);

    default String first() {
        return get("1");
    }

    @Override
    String toString();
}
