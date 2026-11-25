package io.github.rey5137.topolith.spring.boot.errorfixture.payment.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Declared by another module; the account module lets it through. */
@ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
public class PaymentDeclinedException extends RuntimeException {
    public PaymentDeclinedException(String message) {
        super(message);
    }
}
