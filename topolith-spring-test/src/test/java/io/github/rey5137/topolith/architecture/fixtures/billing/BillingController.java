package io.github.rey5137.topolith.architecture.fixtures.billing;

import io.github.rey5137.topolith.architecture.fixtures.billing.api.BillingApi;

/** D-53: a controller implementing its own module's contract is intra-module. */
public class BillingController implements BillingApi {
    private final BillingImpl impl = new BillingImpl();

    @Override
    public String invoice(String order) {
        return impl.invoice(order);
    }
}
