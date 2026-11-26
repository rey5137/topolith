# Topolith — MVP Plan

The working plan: how each milestone breaks into slices. `docs/spec/delivery/62-roadmap.md` owns the milestone
definitions and exit criteria once M0.1 writes it; until then, RFC 0001 §10 does. This file holds what to build, never
progress: status is read from git (`mN-<name>` branches, slice commits, `mN-verified` tags).

Milestones M0–M8 make up release **0.1.0**: M0–M4 cover RFC 0001 §10's "0.1 (MVP)"; M5–M7 add its "0.2: Parity and
loopback" row and the resilience deferred by D-16; M8 hardens and releases. Later releases are planned as milestones
only when 0.1.0 ships.

---

## 1. M0 — Spec and bootstrap

**Goal:** the 0.1 spec written, and a build that can host M1 with every gate already wired.

| Slice | Contents | Done when |
|---|---|---|
| M0.1 | Spec child files for the 0.1 scope from RFC 0001: `api/10..13`, `test/20..21`, `delivery/60..62`, `reference/90`, each rule an `R-*` with `AC-*` criteria; first `TLnnnn` codes | Every routed file exists; every RFC 0001 0.1 item maps to an `R-*` id |
| M0.2 | Core, Boot, optional Cloud, processor, Spring test, samples and BOM | M0.2-owned `delivery/61` ACs |
| M0.3 | ArchUnit layering rules for INV-7, with bad fixtures | M0.3-owned `delivery/61` ACs |
| M0.4 | CI PR gate and acceptance-criterion audit | M0.4-owned `delivery/60` ACs |

**Exit:** the spec covers 0.1; CI green on empty modules; every gate a later milestone needs already runs.

M0.2 creates `topolith-core`, `topolith-spring-boot`, `topolith-spring-cloud`, `topolith-processor`,
`topolith-spring-test` and `samples/`, adds BOM entries, and requires `./mvnw verify` green from a clean checkout.
M0.4 runs the PR gate on JDK 21 and 25 and audits that every AC has a named test or owning milestone.

## 2. M1 — Modules and activation

Spec: `api/10`, `api/11`, `api/13`. Model: `architect-review` required for the descriptor format and the activation
mechanism (D-7), since both are public contracts.

| Slice | Contents | Done when |
|---|---|---|
| M1.1 | Descriptor model and module graph in `topolith-core`: parse, `requires`/`optional`, cycles, duplicate ids | `api/10` model ACs |
| M1.2 | `@TopolithModule` and the annotation processor: descriptor generation, `provides` inference, api descriptor | `api/10` processor ACs |
| M1.3 | Mode resolution and inclusion-based activation (D-7, D-8, D-21) | M1.3-owned `api/11` ACs |
| M1.4 | Per-module persistence and migrations | M1.4-owned `api/11` ACs |
| M1.5 | Startup validation, internal version readiness and mode combinations | M1.5-owned validation and exit ACs |
| M1.6 | Per-module Spring Data JDBC repositories, sharing the JPA registration path (Q-17) | M1.6-owned `api/11` ACs |

**Exit:** a host with two modules runs each combination of `internal` and `none`; INV-2 holds by test.

M1.3 uses an import selector (D-30) and `AutoConfigurationImportFilter`. M1.5 tests startup `TLnnnn` failures with
`ApplicationContextRunner` and covers its `api/13` and milestone-exit ACs.
M1.4 uses filtered `PersistenceManagedTypes`, per-module repositories and `db/modules/<id>` migrations (Flyway or Liquibase)
with per-module tracking tables (D-32), while preserving module-owned persistence configuration and preventing `none` contributions.
M1.6 registers an internal module's Spring Data JDBC repositories per module; other non-JPA stores fail with TL0024 in
0.1 (D-36, Q-17).

## 3. M2 — External mode

Spec: `api/12`. Model: `architect-review` for the error-translation contract and D-3.

| Slice | Contents | Done when |
|---|---|---|
| M2.1 | HTTP groups, fixed URL, timeouts and external isolation | M2.1-owned `api/11` and `api/12` ACs |
| M2.2 | Optional Cloud discovery and missing-starter failure | M2.2-owned `api/12` discovery ACs |
| M2.3 | ProblemDetail errors and transport failures | M2.3-owned `api/12` error ACs |
| M2.4 | Exposure, transaction boundary and version readiness | M2.4-owned `api/12` and `api/13` ACs |
| M2.5 | Custom client escape hatch and hybrid contract tests | M2.5-owned `api/12` and `delivery/62` ACs |
| M2.6 | Refresh (D-11, D-12), restart and failure status | M2.6-owned `api/11` and `api/12` ACs |

**Exit:** a hybrid host (one module internal, one external against a running service) passes the same tests as the
monolith.

M2.1 maps `remote.url` to Boot client properties and registers groups for `@HttpExchange` contracts. M2.2 uses
`topolith-spring-cloud` and Spring Cloud LoadBalancer for `remote.service-id`, or fails with the missing starter.
M2.3 translates API errors through ProblemDetail and uses `ModuleUnavailableException` for transport failures.
M2.4 covers `/<id>`, `expose.enabled`, the `SecurityFilterChain` hook, D-2 propagation, the provider version endpoint
and external readiness including endpoint failures (D-24). M2.5 covers `ModuleClients.configure`, the
separate-HTTP-interface option and the hybrid-versus-monolith contract tests. M2.6 implements D-13 through D-15 and
D-20 in the optional integration, and the missing-integration warning in the Boot module (D-23).

## 4. M3 — Boundaries

Spec: `test/20`, `test/21`.

| Slice | Contents | Done when |
|---|---|---|
| M3.1 | Descriptor-driven ArchUnit boundary rules | M3.1-owned `test/20` and `delivery/61` ACs |
| M3.2 | Frozen baseline and packaged resource generation (D-4, D-25) | M3.2-owned `test/20` ACs |
| M3.3 | Startup ownership and cross-module checks (D-17, D-25) | M3.3-owned `test/20` ACs |
| M3.4 | `topolith-spring-test` helpers | M3.4-owned `test/21` ACs |

**Exit:** a deliberate cross-module internal access fails both the test and startup.

M3.1's `TopolithArchitecture.verify()` checks non-API access, undeclared `requires` or `optional`, cycles and contract
types, each with a deliberately failing fixture. M3.2 generates packaged resources from the frozen ArchUnit store.
M3.3 detects internal cross-module references to non-API classes using those resources. M3.4 supplies per-mode test
configuration and mock-server wiring for external dependencies.

## 5. M4 — Reference adopter and 0.1.0

| Slice | Contents | Done when |
|---|---|---|
| M4.1 | Sample modules, hosts and Maven enforcer rules | Shared Payment tests and enforcer AC pass |
| M4.2 | Testcontainers end-to-end tests and latest-modules CI | CI green, including latest-modules job |
| M4.3 | Getting started, legacy adoption and configuration docs | M4.3-owned `delivery/61` and catalog ACs |
| M4.4 | Maven Central release, CHANGELOG and `@Incubating` | M4.4-owned `delivery/61` ACs |
| M4.5 | Documentation site | M4.5-owned `delivery/61` AC |

**Exit:** 0.1.0 published.

M4.1 supplies user and payment `*-api`, `*-module` and `*-service` artifacts, plus monolith, hybrid and `none` hosts.
M4.2 exercises hybrid fixed-URL and Consul discovery with Testcontainers. M4.4 supplies the `0.1.0` changelog.
M4.5 publishes the user guide as a MkDocs site on GitHub Pages.

## 6. M5 — Parity and loopback

| Slice | Contents | Done when |
|---|---|---|
| M5.1 | Spec for loopback mode, by-value proxy, fault injection and the mode matrix; 0.1.0 scope amended | M5 rules and ACs written; `R-ROAD-01` and exclusions amended |
| M5.2 | By-value (JSON round-trip) test proxy in `topolith-spring-test` | M5.2-owned ACs |
| M5.3 | `loopback` mode: in-process module, calls through real HTTP serialization and error mapping | M5.3-owned ACs, including INV-2 |
| M5.4 | Latency and fault injection for loopback | M5.4-owned ACs |
| M5.5 | Mode-matrix parity test over internal, loopback and external (AC-TEST-05, AC-TEST-06) | Sample Payment tests pass in every mode |

**Model:** `opus` / `high` for M5.1, M5.3 and M5.5 (mode semantics, activation, INV-6 parity).

**Exit:** a deliberately mutable DTO or `Page` return fails the tests.

The RFC's "mutation proxy" (§6) is the by-value proxy of M5.2.

## 7. M6 — Call context and observability

| Slice | Contents | Done when |
|---|---|---|
| M6.1 | Spec for the outgoing-auth SPI, context propagation and Observation conventions | M6 rules and ACs written |
| M6.2 | One `topolith.module.call` Observation in both modes, tags `module.id`, `module.mode`, `caller.module` | M6.2-owned ACs |
| M6.3 | MDC and tenant context propagation across external calls | M6.3-owned ACs |
| M6.4 | Outgoing-auth hook per group, default token relay; the local path carries the same `Authentication` | M6.4-owned ACs |

**Model:** `opus` / `high` for M6.1 and M6.4 (public SPI, parity).

**Exit:** the same span name and tags in internal and external mode, and a token relayed end to end.

## 8. M7 — Resilience

| Slice | Contents | Done when |
|---|---|---|
| M7.1 | Spec for per-group retry and circuit breaker and the idempotency declaration; reverses D-16, answers Q-23 | M7 rules and ACs written |
| M7.2 | Idempotency declaration in the annotation, processor and descriptor | M7.2-owned ACs |
| M7.3 | Per-group retry and circuit breaker through Spring Cloud; only idempotent calls retried (D-51 holds) | M7.3-owned ACs |
| M7.4 | Refresh behaviour, sample and docs | M7.4-owned ACs |

**Model:** `opus` / `high` for M7.1 and M7.2 (descriptor format, public annotation).

**Exit:** a non-idempotent call is never retried, and declared exception types survive a breaker.

## 9. M8 — Hardening and 0.1.0

| Slice | Contents | Done when |
|---|---|---|
| M8.1 | Owner answers to Q-11, Q-13, Q-16–Q-19, Q-21, Q-22, Q-24, Q-26 written into the spec; code for those that need it | No open Q left unanswered or explicitly deferred |
| M8.2 | Untested paths: fat-JAR nested resources, `java -jar`, DevTools, exploded war, Gradle layout, AOT, load-time weaving | Each has a test or a documented deferral |
| M8.3 | CI runs the getting-started commands; RFC/spec migration-path conflict resolved; Dependabot PRs handled | CI green |
| M8.4 | Docs-site pages for M5–M7, CHANGELOG, `v0.1.0` tag | 0.1.0 published |

**Exit:** 0.1.0 published.
