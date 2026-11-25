package fixture.architecture.shop;

import fixture.architecture.billing.api.BillingApi;

public class ShopService {
    private BillingApi billing;

    public String checkout(String order) {
        return billing.invoice(order);
    }
}
