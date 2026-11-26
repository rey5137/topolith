# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-11-26

### Added

- **Module modes (`internal`, `external`, `none`).** Configure each module per host via `topolith.modules.<id>.mode`.
  Internal modules run in-process through a non-deferred import selector, external modules are consumed over HTTP
  service clients, and `none` modules leave no trace of beans, auto-configurations, entities, repositories, migrations,
  routes, listeners or scheduled tasks (INV-2).
- **Module and API descriptors (`@TopolithModule` and `topolith-processor`).** Annotation processor generating
  `META-INF/topolith/modules/<id>.properties` and `META-INF/topolith/apis/<id>.properties` from `@TopolithModule` on
  `package-info.java`, with compile-time validation of API packages, dependencies and API version options.
- **Startup validation and `TLnnnn` diagnostic codes.** Fail-fast topology validation covering descriptor consistency,
  explicit host modes, required module graphs, persistence and repository registration, host scan boundaries,
  auto-configuration back-off, contract interfaces, runtime non-API boundary checks and packaged freeze baselines.
- **HTTP exposure, remote clients and parity.** Explicit `topolith.modules.<id>.expose.enabled` serving internal module
  controllers under `/<id>`, `ExposedEndpoints` and `ExposedRequestMatcher` security hooks, `NOT_SUPPORTED` contract
  transaction boundary advisor with `topolith.contract.calls.in.transaction` counter, external HTTP service group
  clients (`remote.url`, `remote.service-id`, timeouts, separate HTTP interfaces, `ModuleClients.configure`), contract
  exception problem-details translation, transport `ModuleUnavailableException` mapping and API version readiness
  checks.
- **Spring Cloud discovery, refresh and actuator endpoint (`topolith-spring-cloud`).** Optional LoadBalancer `lb://`
  service-id resolution, `EnvironmentChangeEvent` refresh handling that swaps external endpoint settings live and flags
  mode or wiring changes as `restartPending`, and the `topolith` actuator endpoint reporting running modes and refresh
  status.
- **Testing and architecture rules (`topolith-spring-test`).** `@TopolithModuleMode` annotations, `TopolithTestModes`
  builder, context-owned `TopolithMockServer` with `@TopolithMock` injection and per-test reset, descriptor-driven
  `TopolithArchitecture` ArchUnit rules and `TopolithBaseline` frozen baseline generation.
- **Platform BOM (`topolith-bom`), reference samples and documentation.** Platform bill of materials, reference
  `user` and `payment` modules with monolith, hybrid and `payment-host` topologies, and user guides for getting
  started, legacy adoption and configuration.

### Notes for adopters

- **Every public type is `@Incubating` until 1.0.** Public types across all published modules carry `@Incubating` and
  their shape may change before `1.0.0`.
- **`topolith-bom` is the platform (D-68).** Besides the Topolith artifacts, `topolith-bom` imports
  `spring-boot-dependencies` (`4.2.0`) and `spring-cloud-dependencies` (`2026.0.0`), so importing `topolith-bom` alone
  manages Spring Boot, Spring Cloud and Topolith versions.
- **Architecture rule descriptions are stable keys (D-64).** `TopolithArchitecture` rules use fixed descriptions
  (`topolith: non-API access`, `topolith: undeclared dependency`, `topolith: requires cycle`,
  `topolith: descriptor consistency`, `topolith: Topolith dependency`, `topolith: contract types`). Regenerate any
  ArchUnit freeze stores recorded under earlier prose rule descriptions with `allowStoreCreation=true`.
- **`@TopolithMock` attribute name (D-62 amendment).** The module id attribute on `@TopolithMock` is `id` to match
  `@TopolithModuleMode`: `@TopolithMock("x")` became `@TopolithMock(id = "x")`.
- **Startup TL0012 matches ArchUnit's non-API dependency set (D-63).** The startup non-API boundary class scan checks
  only the class-path root holding the module descriptor, while the packaged baseline
  `META-INF/topolith/baseline/<id>.txt` is read from the module's descriptor root or a descriptor-less directory root
  (never a JAR, failing `TL0030` otherwise). The startup dependency set matches ArchUnit's for `NON_API_ACCESS`; casts,
  array creation, inlined constants, accessed field types and called-member signature types are no longer checked.

[Unreleased]: https://github.com/rey5137/topolith/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/rey5137/topolith/releases/tag/v0.1.0
