package io.github.rey5137.topolith.spring.boot.loopfixture.host;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/** A host whose own HTTP service group is named like the loopback module. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ImportHttpServices(group = "wallet", types = OtherClient.class)
public class GroupHost {
}
