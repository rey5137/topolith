package io.github.rey5137.topolith.spring.boot.errorfixture.checked.api;

public interface AbstractCheckedContract {
    String read() throws AbstractCheckedException;
}
