package io.github.rey5137.topolith.spring.boot.fixture.shelf;

import io.github.rey5137.topolith.spring.boot.fixture.catalog.CatalogItem;

import org.springframework.data.repository.Repository;

/** Registered by a hand-built factory bean that records it as a property. */
public interface MappedRepository extends Repository<CatalogItem, Long> {
}
