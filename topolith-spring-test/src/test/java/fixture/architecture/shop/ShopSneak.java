package fixture.architecture.shop;

import fixture.architecture.billing.BillingImpl;

public class ShopSneak {
    private final BillingImpl impl = new BillingImpl();

    public String refund(String order) {
        return impl.invoice(order);
    }
}
