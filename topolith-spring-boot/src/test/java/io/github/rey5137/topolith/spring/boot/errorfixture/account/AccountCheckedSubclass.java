package io.github.rey5137.topolith.spring.boot.errorfixture.account;

import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountCheckedException;

public class AccountCheckedSubclass extends AccountCheckedException {
    public AccountCheckedSubclass(String message) {
        super(message);
    }
}
