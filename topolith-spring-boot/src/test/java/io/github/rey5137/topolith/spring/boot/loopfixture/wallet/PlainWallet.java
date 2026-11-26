package io.github.rey5137.topolith.spring.boot.loopfixture.wallet;

import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api.Balance;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api.WalletContract;

/** A second provider of the wallet contract, registered by a test (never scanned: no stereotype, no routes). */
public class PlainWallet implements WalletContract {
    @Override
    public Balance deposit(Balance balance) {
        return balance;
    }

    @Override
    public void touch() {
    }

    @Override
    public String slow(long millis) {
        return "plain";
    }

    @Override
    public String transaction() {
        return "plain";
    }

    @Override
    public String header() {
        return "plain";
    }
}
