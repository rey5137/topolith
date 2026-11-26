package io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** The loopback fixture's contract. */
@HttpExchange
public interface WalletContract {
    /** Changes its argument, and keeps the result it returns to change it at the next {@link #touch()}. */
    @PostExchange("/deposit")
    Balance deposit(@RequestBody Balance balance);

    /** Changes the result the last deposit returned. */
    @PostExchange("/touch")
    void touch();

    @GetExchange("/slow")
    String slow(@RequestParam("millis") long millis);

    /**
     * What the provider saw: {@code <thread name>|<request or none>|<whether a transaction is active>}, from one
     * call.
     */
    @GetExchange("/transaction")
    String transaction();

    /** The {@code X-Loopback} request header the provider received, or an empty string. */
    @GetExchange("/header")
    String header();
}
