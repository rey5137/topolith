package io.github.rey5137.topolith.spring.boot.storefixture.mongoown;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ItemRepository extends MongoRepository<Item, String> {
}
