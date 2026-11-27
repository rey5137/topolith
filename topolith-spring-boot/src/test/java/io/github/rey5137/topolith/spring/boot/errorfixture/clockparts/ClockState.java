package io.github.rey5137.topolith.spring.boot.errorfixture.clockparts;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/** What a clock call sees of the caller's transaction, outside every module package. */
public final class ClockState {
    private ClockState() {
    }

    public static String state() {
        return (TransactionSynchronizationManager.isActualTransactionActive() ? "active" : "inactive") + ":"
                + TransactionSynchronizationManager.getCurrentTransactionName();
    }
}
