package io.github.rey5137.topolith.spring.boot.loopfixture.wallet;

import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api.WalletContract;
import org.springframework.stereotype.Component;

/** The loopback module's own injections of its contract and of the provider's concrete class. */
@Component
public class WalletInside {
    public final WalletContract contract;
    public final WalletController concrete;

    public WalletInside(WalletContract contract, WalletController concrete) {
        this.contract = contract;
        this.concrete = concrete;
    }
}
