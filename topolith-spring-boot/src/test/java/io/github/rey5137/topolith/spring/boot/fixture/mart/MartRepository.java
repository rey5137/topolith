package io.github.rey5137.topolith.spring.boot.fixture.mart;

import io.github.rey5137.topolith.spring.boot.fixture.catalog.CatalogItem;

import org.springframework.data.repository.Repository;

public interface MartRepository extends Repository<CatalogItem, Long> {
}
