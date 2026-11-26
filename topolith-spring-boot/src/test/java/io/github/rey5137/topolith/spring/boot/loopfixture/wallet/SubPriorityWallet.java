package io.github.rey5137.topolith.spring.boot.loopfixture.wallet;

/** A provider whose {@code @Priority} is only on its superclass, registered by a test (never scanned). */
public class SubPriorityWallet extends PriorityWallet {
}
