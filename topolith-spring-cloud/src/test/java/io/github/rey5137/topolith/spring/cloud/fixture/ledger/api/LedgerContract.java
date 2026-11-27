package io.github.rey5137.topolith.spring.cloud.fixture.ledger.api;

import io.github.rey5137.topolith.contract.Idempotent;

/** A contract with no HTTP annotations: a caller maps it to a separate interface (D-49). */
public interface LedgerContract {
    @Idempotent
    String total();

    String peek();

    String put();
}
