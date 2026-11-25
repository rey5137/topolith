package io.github.rey5137.topolith.spring.boot.fixture.shelf;

import io.github.rey5137.topolith.spring.boot.fixture.catalog.CatalogItem;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/** Unregistered on purpose. */
@NoRepositoryBean
public interface OptedOutRepository extends Repository<CatalogItem, Long> {
}
