package io.github.rey5137.topolith.spring.boot.storefixture.mongoown;

import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableMongoRepositories(basePackageClasses = ItemRepository.class)
public class OwnConfiguration {
}
