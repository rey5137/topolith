package fixture.invoice;

import fixture.invoice.api.InvoiceCheckedException;

/** Outside the api package: a caller gets its api-package ancestor (D-116). */
public class InvoiceCheckedSub extends InvoiceCheckedException {
    public InvoiceCheckedSub(String message) { super(message); }
}
