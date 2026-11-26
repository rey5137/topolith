package io.github.rey5137.topolith.samples.user.api;

import java.math.BigDecimal;

/** An order request, carried by value in every mode. */
public record PlaceOrderRequest(String reference, BigDecimal amount) {
}
