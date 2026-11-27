package io.github.rey5137.topolith.spring.cloud.fixture.ledger;

import io.github.rey5137.topolith.spring.cloud.fixture.ledger.api.LedgerContract;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PutExchange;

/** The caller's exchange interface of {@link LedgerContract}. */
@HttpExchange
public interface LedgerHttp extends LedgerContract {
    @Override
    @GetExchange("/total")
    String total();

    @Override
    @GetExchange("/peek")
    String peek();

    @Override
    @PutExchange("/put")
    String put();
}
