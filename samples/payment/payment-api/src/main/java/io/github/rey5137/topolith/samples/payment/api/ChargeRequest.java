package io.github.rey5137.topolith.samples.payment.api;

import java.math.BigDecimal;
import java.util.List;

// snippet:start charge-request
/**
 * A charge request, carried by value in every mode. The tag list is copied into an unmodifiable list, so neither the
 * caller's later edits nor a provider can change what the other side holds (INV-6).
 */
public record ChargeRequest(String reference, BigDecimal amount, List<String> tags) {
    public ChargeRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public ChargeRequest(String reference, BigDecimal amount) {
        this(reference, amount, List.of());
    }
}
// snippet:end charge-request
