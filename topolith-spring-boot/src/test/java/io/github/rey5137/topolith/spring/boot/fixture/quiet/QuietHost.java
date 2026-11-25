package io.github.rey5137.topolith.spring.boot.fixture.quiet;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** A host in a package with no module and no repository. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class QuietHost {
}
