# 21 — Test support

This file specifies 0.1 test helpers for selecting module modes and wiring external dependencies.
It also specifies the shared tests for the reference topologies.

## Rules

### R-TEST-01 — Mode configuration

`topolith-spring-test` supplies per-mode test configuration for the supported 0.1 modes. Implements INV-3, P-3, D-18 and D-62.

- AC-TEST-01 (M3.4): A test can select `internal`, `external` or `none` for a module through the helper. The
  annotation overrides application config only: when inlined test properties (`@SpringBootTest(properties)`,
  `@TestPropertySource(properties)`) or dynamic properties set the same `mode`, `remote.url` or `remote.service-id` to a
  different value, context load fails with `IllegalStateException` naming the module, the key and both values; a
  same-valued property passes. The check covers `@TestPropertySource` files (also with a placeholder location or a
  custom `factory`) and `DynamicPropertyRegistrar` beans, and annotations on a superclass or enclosing class. An
  `external`
  selection (annotation or builder) sets `remote.service-id` to the empty string, so a host that configures a service id
  starts and the mock URL wins. Selecting `internal` or `none` leaves a host service id untouched.
  The builder's `applyTo` throws `IllegalStateException` naming a module that was selected without a mode. The context
  customizer factory and the test execution listener are package-private and load through `spring.factories`.
- AC-TEST-02 (M3.4): Helper-selected `none` leaves no implementation contributions in the test context, also when the
  host configures a service id.

### R-TEST-02 — External dependency test wiring

`topolith-spring-test` provides mock-server wiring for external dependencies. Implements INV-6, P-2, D-18 and D-62.

- AC-TEST-03 (M3.4): An external contract call reaches a test-configured mock server through the helper. A
  context-owned mock server's stubs and recorded requests are cleared before and after each test method (as Spring
  Boot's `MockReset` does for mock beans), so tests sharing a cached context never see each other's stubs or calls. The
  mock serves but never records the readiness check's `GET /<id>/.topolith/api-version` (D-65), so `requests()` holds
  only the test's own calls whenever the check arrives (at ready or after a refresh, D-50). `@BeforeEach` stubs survive (the reset runs before them); `@BeforeAll`
  stubs do not. `reset()` leaves no built-in endpoint to lose: an unstubbed path, including
  `/<id>/.topolith/api-version`, answers 404 before and after a reset. The listener creates no lazy bean.
- AC-TEST-04 (M3.4): A mock-server error exercises the external client's API exception mapping.
  `problem(method, path, type, message)` writes the status the provider writes (merged `@ResponseStatus`, else 500; a
  non-error status becomes 500); the explicit-status overload rejects a status outside 400-599, or one that differs from a
  `@ResponseStatus`-derived status, with `IllegalArgumentException`; both overloads reject `ModuleUnavailableException`
  the same way; `unavailable(method, path, downstreamModuleId)` writes the provider's 503 with its fixed
  detail and the downstream module id. Status, content type and the `type`, `status`, `detail`, `exception` and
  `module` members equal `ModuleExceptionResolver`'s output for a declared exception with a `@ResponseStatus`, one
  without, and `ModuleUnavailableException`. The mock writes the exception class it is given, so pass the declared
  contract type.

### R-TEST-03 — Reference topology tests

The same payment tests exercise the monolith, hybrid and `none` hosts; the `none` case checks absent contributions.
Implements INV-2 and INV-6.

- AC-TEST-05 (M4.1): The same payment test cases pass in monolith, hybrid and `none` host configurations.
- AC-TEST-06 (M4.1): The `none` host has no disabled-module beans, routes, tables or auto-configurations.
- AC-TEST-07 (M4.2): Hybrid end-to-end tests exercise both a fixed URL and Consul discovery with Testcontainers.
