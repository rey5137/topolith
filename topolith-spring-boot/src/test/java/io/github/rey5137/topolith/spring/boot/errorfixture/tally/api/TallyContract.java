package io.github.rey5137.topolith.spring.boot.errorfixture.tally.api;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** A contract with HTTP routes declared, which the tally module implements only by a non-handler service. */
@HttpExchange
public interface TallyContract {
    @GetExchange("/count")
    String count();

    @GetExchange("/reset")
    String reset();
}
