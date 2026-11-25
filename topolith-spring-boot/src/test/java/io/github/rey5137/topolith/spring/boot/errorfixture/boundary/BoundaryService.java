package io.github.rey5137.topolith.spring.boot.errorfixture.boundary;

import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.api.BoundaryContract;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class BoundaryService implements BoundaryContract {
    /** The current transaction as {@code active|inactive:<name>}. */
    public static String state() {
        return (TransactionSynchronizationManager.isActualTransactionActive() ? "active" : "inactive") + ":"
                + TransactionSynchronizationManager.getCurrentTransactionName();
    }

    @Override
    public String plain() {
        return state();
    }

    @Override
    @Transactional
    public String own() {
        return state();
    }

    @Override
    public String fail() {
        throw new IllegalStateException("callee failed");
    }

    /** Public, but not part of the contract. */
    public String helper() {
        return state();
    }
}
