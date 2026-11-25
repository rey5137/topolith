package io.github.rey5137.topolith.spring.boot.fixture.billing;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingRepository extends JpaRepository<BillingEntry, Long> {
}
