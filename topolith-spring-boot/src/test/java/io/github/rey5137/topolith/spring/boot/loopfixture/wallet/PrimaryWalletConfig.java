package io.github.rey5137.topolith.spring.boot.loopfixture.wallet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A lite configuration (no stereotype, so never scanned) whose {@code @Bean @Primary} provides the wallet contract. */
public class PrimaryWalletConfig {
    @Bean
    @Primary
    public PlainWallet primaryWallet() {
        return new PlainWallet();
    }
}
