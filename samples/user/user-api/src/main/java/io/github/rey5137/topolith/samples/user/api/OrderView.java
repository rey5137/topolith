package io.github.rey5137.topolith.samples.user.api;

import java.math.BigDecimal;

/** A placed order, carried by value in every mode. */
public record OrderView(String id, String paymentId, String reference, BigDecimal amount) {
}
