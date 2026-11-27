package io.github.rey5137.topolith.spring.boot.errorfixture.idem.api;

import io.github.rey5137.topolith.contract.Idempotent;
import org.springframework.web.service.annotation.GetExchange;

/** A superinterface whose methods the contracts that extend it inherit as idempotent (D-100). */
@Idempotent
public interface Lookup {
    @GetExchange("/count")
    int count();
}
