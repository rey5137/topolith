package io.github.rey5137.topolith.spring.boot.storefixture.r2dbc;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface R2dbcItemRepository extends ReactiveCrudRepository<R2dbcItem, Long> {
}
