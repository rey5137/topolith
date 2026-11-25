package io.github.rey5137.topolith.spring.boot.jdbcfixture.jdbcown;

import org.springframework.data.repository.CrudRepository;

public interface OwnedItemRepository extends CrudRepository<OwnedItem, Long> {
}
