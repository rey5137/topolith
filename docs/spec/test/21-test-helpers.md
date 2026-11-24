# 21 — Test support

This file specifies 0.1 test helpers for selecting module modes and wiring external dependencies.
It also specifies the shared tests for the reference topologies.

## Rules

### R-TEST-01 — Mode configuration

`topolith-spring-test` supplies per-mode test configuration for the supported 0.1 modes. Implements INV-3, P-3 and D-18.

- AC-TEST-01 (M3.4): A test can select `internal`, `external` or `none` for a module through the helper.
- AC-TEST-02 (M3.4): Helper-selected `none` leaves no implementation contributions in the test context.

### R-TEST-02 — External dependency test wiring

`topolith-spring-test` provides mock-server wiring for external dependencies. Implements INV-6, P-2 and D-18.

- AC-TEST-03 (M3.4): An external contract call reaches a test-configured mock server through the helper.
- AC-TEST-04 (M3.4): A mock-server error exercises the external client's API exception mapping.

### R-TEST-03 — Reference topology tests

The same payment tests exercise the monolith, hybrid and `none` hosts; the `none` case checks absent contributions.
Implements INV-2 and INV-6.

- AC-TEST-05 (M4.1): The same payment test cases pass in monolith, hybrid and `none` host configurations.
- AC-TEST-06 (M4.1): The `none` host has no disabled-module beans, routes, tables or auto-configurations.
- AC-TEST-07 (M4.2): Hybrid end-to-end tests exercise both a fixed URL and Consul discovery with Testcontainers.
