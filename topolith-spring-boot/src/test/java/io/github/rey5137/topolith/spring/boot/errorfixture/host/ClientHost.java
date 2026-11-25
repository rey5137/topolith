package io.github.rey5137.topolith.spring.boot.errorfixture.host;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/** A caller host that also declares its own service group. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ImportHttpServices(group = "host", types = HostClient.class)
public class ClientHost {
}
