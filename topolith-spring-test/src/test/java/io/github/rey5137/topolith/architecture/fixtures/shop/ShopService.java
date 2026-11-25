package io.github.rey5137.topolith.architecture.fixtures.shop;

import io.github.rey5137.topolith.architecture.fixtures.billing.api.BillingApi;

public class ShopService {
    private BillingApi billing;

    public String checkout(String order) {
        return billing.invoice(order);
    }
}
