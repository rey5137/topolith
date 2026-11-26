package io.github.rey5137.topolith.spring.boot.loopfixture.host;

import java.util.List;

import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api.WalletContract;

/** A host bean that calls the loopback module and collects every {@link Auditable}. */
public class WalletCaller {
    public final WalletContract wallet;
    public final List<Auditable> audits;

    public WalletCaller(WalletContract wallet, List<Auditable> audits) {
        this.wallet = wallet;
        this.audits = audits;
    }
}
