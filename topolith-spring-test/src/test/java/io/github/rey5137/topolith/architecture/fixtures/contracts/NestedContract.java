package io.github.rey5137.topolith.architecture.fixtures.contracts;

import org.springframework.data.domain.Page;

public interface NestedContract {
    interface OrderApi {
        Page<String> list();
    }
}
