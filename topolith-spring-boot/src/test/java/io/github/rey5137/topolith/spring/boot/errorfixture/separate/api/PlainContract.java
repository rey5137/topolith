package io.github.rey5137.topolith.spring.boot.errorfixture.separate.api;

public interface PlainContract {
    String find(String value) throws PlainException;
}
