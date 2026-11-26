package io.github.rey5137.topolith.samples.payment.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A declined charge; a declared contract exception crossing HTTP as ProblemDetail. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class PaymentDeclinedException extends RuntimeException {
    public PaymentDeclinedException(String message) {
        super(message);
    }
}
