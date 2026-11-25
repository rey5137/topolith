package io.github.rey5137.topolith.spring.boot.fixture.mart;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** A standalone service's application class, inside its module package, with a repository no store registers. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class MartApplication {
}
