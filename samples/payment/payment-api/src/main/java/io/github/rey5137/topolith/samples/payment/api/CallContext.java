package io.github.rey5137.topolith.samples.payment.api;

/** What the payment provider saw of its caller's context: a listed MDC value and the tenant, null when absent. */
public record CallContext(String requestId, String tenant) {
}
