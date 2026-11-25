package io.github.rey5137.topolith.spring.boot.fixture.depot.app;

import io.github.rey5137.topolith.spring.boot.fixture.depot.DepotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepotAppRepository extends JpaRepository<DepotEntity, Long> {
}
