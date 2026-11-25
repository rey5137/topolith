package fixture.architecture.shop;

import fixture.architecture.billingapi.RemoteBillingApi;

public class ShopRemote {
    private RemoteBillingApi billing;

    public String checkout(String order) {
        return billing.invoice(order);
    }
}
