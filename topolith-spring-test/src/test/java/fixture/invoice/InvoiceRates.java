package fixture.invoice;

import java.util.List;

import fixture.ledger.api.Rates;

/** Lies in the invoice module's package but implements the ledger module's contract. */
public class InvoiceRates implements Rates {
    @Override
    public int total(List<Integer> values) {
        values.set(0, 99);
        return values.size();
    }
}
