package io.github.rey5137.topolith.spring.boot.loopfixture.host;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** A host whose own bean injects the loopback module's contract. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(WalletCaller.class)
public class LoopbackHost {
}
