package io.github.rey5137.topolith.samples.payment.api;

import java.util.List;

/** Who the payment provider sees calling: the principal name and its sorted authorities, or none (null name). */
public record CallerIdentity(String name, List<String> authorities) {
}
