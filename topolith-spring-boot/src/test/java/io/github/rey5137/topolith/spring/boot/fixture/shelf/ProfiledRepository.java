package io.github.rey5137.topolith.spring.boot.fixture.shelf;

import io.github.rey5137.topolith.spring.boot.fixture.catalog.CatalogItem;

import org.springframework.context.annotation.Profile;
import org.springframework.data.repository.Repository;

/** Only exists outside the prod profile. */
@Profile("!prod")
public interface ProfiledRepository extends Repository<CatalogItem, Long> {
}
