package fixture.architecture.shop;

import fixture.architecture.billing.BillingImpl;

public class ShopHack {
    private final BillingImpl impl = new BillingImpl();

    public String checkout(String order) {
        return impl.invoice(order);
    }
}
