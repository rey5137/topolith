package io.github.rey5137.topolith.architecture.fixtures.shop;

import io.github.rey5137.topolith.architecture.fixtures.billingapi.RemoteBillingApi;

public class ShopRemote {
    private RemoteBillingApi billing;

    public String checkout(String order) {
        return billing.invoice(order);
    }
}
