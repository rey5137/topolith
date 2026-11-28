# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

Planned as 0.1.0.

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

- **Loopback and parity (`topolith.modules.<id>.mode=loopback`).** Exercise in-process implementations through HTTP,
  with by-value contract checks, configurable latency and fault injection, and an internal/loopback/external test
  matrix.
- **Call context and observability (`topolith.module.call`).** Observations and tags cover calls in every mode;
  `topolith.context.mdc-keys` propagates selected MDC values alongside tenant context. `ModuleClients.authenticate`
  supplies outgoing authentication, including token relay.
- **Resilience (`@Idempotent`).** Declare safe contract calls and configure per-group retry and circuit breakers for
  external and loopback clients.
- **Startup guards (TL0042 and TL0043).** Detect excluded module activation auto-configuration and host MyBatis mapper
  locations that include module XML, alongside checks for modules without contracts.
- **Consumer-implemented contracts (`@ConsumerImplemented` and `provides`).** Declare who supplies a contract and
  expose listed contracts through Topolith-registered routes.
- **Module persistence (`db/modules/<id>`).** Run Pattern A migrations against the module DataSource and register
  per-module R2DBC and MongoDB repositories and MyBatis mappers.
- **HTTP failure parity (`Topolith-Module`).** Declared checked exceptions cross HTTP; bare 4xx responses map to
  unavailability, with a response header marking Topolith module responses.
- **Default by-value tests (`topolith.test.parity`).** Internal contracts are checked by default; `warn` logs TL0045
  instead of failing on parity violations.
- **Session relay and context (`topolith.modules.<id>.auth.type`).** Session-token relay supports outgoing calls, with
  TL0046 and TL0047 diagnostics; `ContextCarrier` extends propagated context.
- **Listed-route clashes (`TL0048`).** A route Topolith registers for a listed contract that clashes with an existing
  mapping, also when only path-variable names differ, fails startup with TL0048.
- **Packaged application checks (`topolith.modules.<id>.mode`).** Tests cover packaged and extracted JARs, nested roots
  and restart-style class loaders.

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

- **Internal topology (D-83).** `Topology.internal()` includes both internal and loopback modules.
- **Mode ordinals.** `Mode.LOOPBACK` shifts enum ordinals; migrate any persisted `Mode.ordinal()` values.
- **By-value proxy default (D-119).** Spring test contexts with internal contract providers need a JSON HTTP message
  converter. Set `topolith.test.parity=warn` to log parity violations instead of failing.
- **Migration paths (D-32).** Place module migrations under `db/modules/<id>`.
- **Deferred environment coverage (D-132).** See [Tested environments](docs/site/docs/roadmap.md#tested-environments)
  for integrations and environments still awaiting end-to-end coverage.

[Unreleased]: https://github.com/rey5137/topolith/commits/main
