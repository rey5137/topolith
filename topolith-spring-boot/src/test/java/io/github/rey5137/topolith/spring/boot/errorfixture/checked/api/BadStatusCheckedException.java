package io.github.rey5137.topolith.spring.boot.errorfixture.checked.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.ACCEPTED)
public class BadStatusCheckedException extends Exception {
    public BadStatusCheckedException(String message) {
        super(message);
    }
}
