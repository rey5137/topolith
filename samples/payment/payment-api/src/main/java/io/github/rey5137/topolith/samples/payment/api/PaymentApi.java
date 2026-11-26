package io.github.rey5137.topolith.samples.payment.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** The payment contract every topology consumes. */
@HttpExchange
public interface PaymentApi {
    /** Charges a payment and returns the stored result. */
    @PostExchange("/charges")
    ChargeResult charge(@RequestBody ChargeRequest request);

    /** Finds a charge by id. */
    @GetExchange("/charges/{id}")
    ChargeResult find(@PathVariable("id") String id);
}
