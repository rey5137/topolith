package io.github.rey5137.topolith.spring.boot.storefixture.r2dbcown;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface ItemRepository extends ReactiveCrudRepository<Item, String> {
}
