package io.github.rey5137.topolith.spring.cloud.fixture.orders.api;

/** A declared contract exception of the orders module. */
public class OrderRejectedException extends RuntimeException {
    public OrderRejectedException(String message) {
        super(message);
    }
}
