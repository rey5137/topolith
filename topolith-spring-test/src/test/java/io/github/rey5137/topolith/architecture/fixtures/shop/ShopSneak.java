package io.github.rey5137.topolith.architecture.fixtures.shop;

import io.github.rey5137.topolith.architecture.fixtures.billing.BillingImpl;

public class ShopSneak {
    private final BillingImpl impl = new BillingImpl();

    public String refund(String order) {
        return impl.invoice(order);
    }
}
