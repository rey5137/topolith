package io.github.rey5137.topolith.spring.boot.errorfixture.checked.api;

public class ApiCheckedException extends Exception {
    public ApiCheckedException(String message) {
        super(message);
    }
}
