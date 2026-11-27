package io.github.rey5137.topolith.spring.boot.errorfixture.checked;

import io.github.rey5137.topolith.spring.boot.errorfixture.checked.api.ApiCheckedException;

public class OutsideCheckedException extends ApiCheckedException {
    public OutsideCheckedException(String message) {
        super(message);
    }
}
