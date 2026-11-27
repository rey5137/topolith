package io.github.rey5137.topolith.spring.boot.storefixture.mongohost;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface HostItemRepository extends MongoRepository<Item, String> {
}
