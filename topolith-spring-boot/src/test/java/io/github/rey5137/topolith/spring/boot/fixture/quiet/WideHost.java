package io.github.rey5137.topolith.spring.boot.fixture.quiet;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** A host whose auto-configuration packages include the parent of every fixture module package. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = "io.github.rey5137.topolith.spring.boot.fixture")
public class WideHost {
}
