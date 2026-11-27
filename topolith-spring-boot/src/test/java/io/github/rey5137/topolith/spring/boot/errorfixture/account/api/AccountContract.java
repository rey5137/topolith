package io.github.rey5137.topolith.spring.boot.errorfixture.account.api;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** A contract whose implementation throws each kind of exception R-EXT-04 distinguishes, chosen by {@code case}. */
@HttpExchange
public interface AccountContract {
    @GetExchange("/find")
    String find(@RequestParam("case") String failure);

    /** Starts a JSON answer, then throws a declared exception. */
    @GetExchange("/partial")
    String partial();

    /** A default method calling contract methods through this interface. */
    default String findTwice(String failure) {
        find(failure);
        return find(failure);
    }
}
