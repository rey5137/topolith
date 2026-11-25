package io.github.rey5137.topolith.spring.boot.fixture.service;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** A standalone service's application class: its package holds no entity and no module. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class ServiceHost {
}
