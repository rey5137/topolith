package io.github.rey5137.topolith.spring.boot.errorfixture.cover.api;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** A generic superinterface: its implementation in a controller is reached through a bridge method. */
public interface Store<T> {
    @PostExchange("/good/put")
    T put(@RequestBody T value);
}
