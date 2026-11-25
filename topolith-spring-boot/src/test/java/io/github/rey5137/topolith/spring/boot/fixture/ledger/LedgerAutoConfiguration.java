package io.github.rey5137.topolith.spring.boot.fixture.ledger;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class LedgerAutoConfiguration {
    @Bean("ledgerMarker")
    Object marker() {
        return new Object();
    }
}
