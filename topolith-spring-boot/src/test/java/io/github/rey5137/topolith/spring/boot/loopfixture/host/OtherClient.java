package io.github.rey5137.topolith.spring.boot.loopfixture.host;

import org.springframework.web.service.annotation.GetExchange;

/** A host HTTP interface that is not a contract of the loopback module. */
public interface OtherClient {
    @GetExchange("/other")
    String other();
}
