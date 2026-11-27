package io.github.rey5137.topolith.spring.boot.storefixture.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MongoItemRepository extends MongoRepository<MongoItem, String> {
}
