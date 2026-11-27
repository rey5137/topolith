package io.github.rey5137.topolith.spring.boot.errorfixture.child.api;

class HiddenChild extends ParentException {
    public HiddenChild(String message) { super(message); }
}
