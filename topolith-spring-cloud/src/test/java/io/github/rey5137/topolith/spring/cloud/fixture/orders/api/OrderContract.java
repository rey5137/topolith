package io.github.rey5137.topolith.spring.cloud.fixture.orders.api;

import io.github.rey5137.topolith.contract.Idempotent;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

/** A contract with idempotent and unannotated methods of every HTTP method (R-EXT-14). */
@HttpExchange
public interface OrderContract {
    @GetExchange("/read")
    @Idempotent
    String read();

    @PutExchange("/replace")
    @Idempotent
    String replace();

    @GetExchange("/peek")
    String peek();

    @PutExchange("/save")
    String save();

    @PostExchange("/create")
    String create();

    @DeleteExchange("/remove")
    String remove();

    /** Throws {@link OrderRejectedException} on the provider. */
    @GetExchange("/reject")
    @Idempotent
    String reject();

    /** Answers 404 with no Topolith problem on the stub provider. */
    @GetExchange("/missing")
    @Idempotent
    String missing();
}
