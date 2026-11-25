package io.github.rey5137.topolith.spring.boot.errorfixture.account.api;

/** Abstract, so it is never the declared class itself: a declared exception walks past it. */
public abstract class AccountFailure extends RuntimeException {
    protected AccountFailure(String message) {
        super(message);
    }
}
