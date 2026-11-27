package io.github.rey5137.topolith.spring.boot.errorfixture.checked.api;

public abstract class AbstractCheckedException extends Exception {
    public AbstractCheckedException(String message) {
        super(message);
    }
}
