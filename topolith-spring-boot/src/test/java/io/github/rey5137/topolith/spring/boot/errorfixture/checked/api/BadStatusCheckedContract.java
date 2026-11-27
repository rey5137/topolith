package io.github.rey5137.topolith.spring.boot.errorfixture.checked.api;

public interface BadStatusCheckedContract {
    String read() throws BadStatusCheckedException;
}
