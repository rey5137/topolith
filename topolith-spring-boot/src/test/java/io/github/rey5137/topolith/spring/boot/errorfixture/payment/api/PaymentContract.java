package io.github.rey5137.topolith.spring.boot.errorfixture.payment.api;

/** A contract the account module's handler calls, to tell a call a handler makes from the handler's own invocation. */
public interface PaymentContract {
    String pay();
}
