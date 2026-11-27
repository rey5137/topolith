package io.github.rey5137.topolith.spring.boot.errorfixture.child.api;

public class ChildException extends ParentException {
    private ChildException(String message) { super(message); }
}
