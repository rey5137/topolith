package io.github.rey5137.topolith.spring.boot.errorfixture.checked.api;

import io.github.rey5137.topolith.spring.boot.errorfixture.checked.OutsideCheckedException;

public interface OutsideCheckedContract {
    String read() throws OutsideCheckedException;
}
