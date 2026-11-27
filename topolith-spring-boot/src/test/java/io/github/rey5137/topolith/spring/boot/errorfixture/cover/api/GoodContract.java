package io.github.rey5137.topolith.spring.boot.errorfixture.cover.api;

import org.springframework.web.service.annotation.GetExchange;

/** Every method has a route: a generic inherited one, a plain inherited one, its own, and a redeclared Object one. */
public interface GoodContract extends Store<String>, Pinger {
    @GetExchange("/good/name")
    String name();

    @Override
    String toString();
}
