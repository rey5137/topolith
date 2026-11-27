package io.github.rey5137.topolith.spring.boot.errorfixture.checked.api;

public class BrokenCheckedException extends Exception {
    public BrokenCheckedException(int value) {
        super(String.valueOf(value));
    }
}
