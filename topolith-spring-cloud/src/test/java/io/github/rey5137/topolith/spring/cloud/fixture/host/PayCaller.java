package io.github.rey5137.topolith.spring.cloud.fixture.host;

import io.github.rey5137.topolith.spring.cloud.fixture.pay.api.PayContract;

/** A host bean that calls the pay module through its contract. */
public class PayCaller {
    public final PayContract pay;

    public PayCaller(PayContract pay) {
        this.pay = pay;
    }
}
