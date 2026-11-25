package io.github.rey5137.topolith.spring.boot.fixture.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {
}
