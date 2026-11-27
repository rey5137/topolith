package io.github.rey5137.topolith.spring.boot.storefixture.r2dbchost;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface HostItemRepository extends ReactiveCrudRepository<Item, String> {
}
