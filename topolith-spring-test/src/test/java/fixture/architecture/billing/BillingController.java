package fixture.architecture.billing;

import fixture.architecture.billing.api.BillingApi;

/** D-53: a controller implementing its own module's contract is intra-module. */
public class BillingController implements BillingApi {
    private final BillingImpl impl = new BillingImpl();

    @Override
    public String invoice(String order) {
        return impl.invoice(order);
    }
}
