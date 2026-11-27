package io.github.rey5137.topolith.spring.boot.errorfixture.child.api;

@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.OK)
public class BadStatusChild extends ParentException {
    public BadStatusChild(String message) { super(message); }
}
