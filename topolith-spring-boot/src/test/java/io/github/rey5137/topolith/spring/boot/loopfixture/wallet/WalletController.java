package io.github.rey5137.topolith.spring.boot.loopfixture.wallet;

import io.github.rey5137.topolith.spring.boot.loopfixture.host.Auditable;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api.Balance;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api.WalletContract;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The provider of {@link WalletContract}; it also implements a host interface. */
@RestController
public class WalletController implements WalletContract, Auditable {
    private volatile Balance returned;

    @Override
    public Balance deposit(Balance balance) {
        balance.getNotes().add("changed by the provider");
        Balance result = new Balance(balance.getAmount() * 2);
        result.getNotes().add("returned");
        returned = result;
        return result;
    }

    @Override
    public void touch() {
        Balance last = returned;
        if (last != null) {
            last.setAmount(-1);
            last.getNotes().add("changed after the call");
        }
    }

    @Override
    public String slow(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "late";
    }

    @Override
    public String transaction() {
        boolean request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes;
        return Thread.currentThread().getName() + "|" + (request ? "request" : "none") + "|"
                + TransactionSynchronizationManager.isActualTransactionActive();
    }

    @Override
    public String header() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String value = attributes.getRequest().getHeader("X-Loopback");
            return value == null ? "" : value;
        }
        return "";
    }

    @Override
    public String audit() {
        return "wallet";
    }
}
