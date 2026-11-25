package io.github.rey5137.topolith.spring.boot.errorfixture.status.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A success status: a caller would read the problem as a result. */
@ResponseStatus(HttpStatus.ACCEPTED)
public class AcceptedException extends RuntimeException {
    public AcceptedException(String message) {
        super(message);
    }
}
