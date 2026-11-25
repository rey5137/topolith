package io.github.rey5137.topolith.spring.boot.errorfixture.account;

import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountException;

/** An implementation subclass outside the api package: a caller sees its declared ancestor. */
public class LockedAccountException extends AccountException {
    public LockedAccountException(String message) {
        super(message);
    }
}
