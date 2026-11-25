package io.github.rey5137.topolith.spring.boot.fixture.local;

import io.github.rey5137.topolith.spring.boot.fixture.local.api.LocalContract;
import org.springframework.stereotype.Component;

@Component
public class LocalComponent implements LocalContract {
    @Override
    public String ping() {
        return "local";
    }
}
