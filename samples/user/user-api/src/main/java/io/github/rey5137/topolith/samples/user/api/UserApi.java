package io.github.rey5137.topolith.samples.user.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** The user contract; its order route charges payment through the configured mode. */
@HttpExchange
public interface UserApi {
    /** Places an order and charges it through payment. */
    @PostExchange("/orders")
    OrderView placeOrder(@RequestBody PlaceOrderRequest request);

    /** Finds an order by id. */
    @GetExchange("/orders/{id}")
    OrderView findOrder(@PathVariable("id") String id);
}
