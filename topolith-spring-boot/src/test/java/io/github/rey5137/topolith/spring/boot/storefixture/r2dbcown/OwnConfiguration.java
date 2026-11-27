package io.github.rey5137.topolith.spring.boot.storefixture.r2dbcown;

import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableR2dbcRepositories(basePackageClasses = ItemRepository.class)
public class OwnConfiguration {
}
