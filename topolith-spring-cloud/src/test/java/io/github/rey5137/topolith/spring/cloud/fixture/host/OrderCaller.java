package io.github.rey5137.topolith.spring.cloud.fixture.host;

import io.github.rey5137.topolith.spring.cloud.fixture.orders.api.OrderContract;

/** A host bean that calls the orders module through its contract. */
public class OrderCaller {
    public final OrderContract orders;

    public OrderCaller(OrderContract orders) {
        this.orders = orders;
    }
}
