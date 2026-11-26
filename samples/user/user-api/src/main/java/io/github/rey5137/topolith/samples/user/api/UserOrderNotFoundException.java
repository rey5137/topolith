package io.github.rey5137.topolith.samples.user.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An unknown order; a declared contract exception crossing HTTP as ProblemDetail. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class UserOrderNotFoundException extends RuntimeException {
    public UserOrderNotFoundException(String message) {
        super(message);
    }
}
