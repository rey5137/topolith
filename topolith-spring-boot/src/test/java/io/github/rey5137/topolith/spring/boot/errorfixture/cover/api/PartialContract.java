package io.github.rey5137.topolith.spring.boot.errorfixture.cover.api;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/** One overload has a route and one has none, and one method carries no exchange annotation at all. */
public interface PartialContract {
    @GetExchange("/partial/find")
    String find(@RequestParam("q") String query);

    String find(String query, int limit);

    String pending();
}
