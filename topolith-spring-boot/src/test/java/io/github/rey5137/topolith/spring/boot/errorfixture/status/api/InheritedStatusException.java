package io.github.rey5137.topolith.spring.boot.errorfixture.status.api;

/** Inherits its superclass's success status. */
public class InheritedStatusException extends AcceptedException {
    public InheritedStatusException(String message) {
        super(message);
    }
}
