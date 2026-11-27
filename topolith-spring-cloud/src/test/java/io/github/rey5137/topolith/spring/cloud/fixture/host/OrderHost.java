package io.github.rey5137.topolith.spring.cloud.fixture.host;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(OrderCaller.class)
public class OrderHost {
}
