package io.github.rey5137.topolith.samples.payment.api;

import java.math.BigDecimal;
import java.util.List;

/** A stored charge, carried by value in every mode; the tag list is unmodifiable. */
public record ChargeResult(String id, String reference, BigDecimal amount, String status, List<String> tags) {
    public ChargeResult {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
