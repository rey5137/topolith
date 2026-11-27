package io.github.rey5137.topolith.spring.boot.errorfixture.checked.api;

public interface BrokenCheckedContract {
    String read() throws BrokenCheckedException;
}
