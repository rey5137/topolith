package io.github.rey5137.topolith.spring.boot.loopfixture.shop;

import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api.WalletContract;
import org.springframework.stereotype.Component;

/** Another internal module that calls the loopback module. */
@Component
public class ShopService {
    public final WalletContract wallet;

    public ShopService(WalletContract wallet) {
        this.wallet = wallet;
    }
}
