package io.github.rey5137.topolith.spring.boot.loopfixture.wallet;

import jakarta.annotation.Priority;

/** A second provider that carries {@code @Priority}, registered by a test (never scanned: no stereotype). */
@Priority(1)
public class PriorityWallet extends PlainWallet {
}
