package io.github.rey5137.topolith.spring.boot.fixture.shelf;

import io.github.rey5137.topolith.spring.boot.fixture.catalog.CatalogItem;

import org.springframework.data.repository.Repository;

/** Holds a member repository interface, which Spring Data's default scan skips. */
public class ShelfHolder {
    public interface NestedRepository extends Repository<CatalogItem, Long> {
    }
}
