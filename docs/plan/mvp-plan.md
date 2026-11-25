# Topolith — MVP Plan

The working plan: how each milestone breaks into slices. `docs/spec/delivery/62-roadmap.md` owns the milestone
definitions and exit criteria once M0.1 writes it; until then, RFC 0001 §10 does. This file holds what to build, never
progress: status is read from git (`mN-<name>` branches, slice commits, `mN-verified` tags).

Milestones M0–M4 make up release **0.1.0** (RFC 0001 §10, "0.1 (MVP)"). Later releases are planned as milestones only
when 0.1.0 ships.

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

**Exit:** a host with two modules runs each combination of `internal` and `none`; INV-2 holds by test.

M1.3 uses `TopolithImportRegistrar` and `AutoConfigurationImportFilter`. M1.5 tests startup `TLnnnn` failures with
`ApplicationContextRunner` and covers its `api/13` and milestone-exit ACs.
M1.4 uses filtered `PersistenceManagedTypes`, per-module repositories and `db/migration/<id>` locations, while
preserving module-owned persistence configuration and preventing `none` contributions.

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
and external readiness including endpoint failures (D-24). M2.5 covers `ModuleClients.restClientBuilder`, the
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

**Exit:** 0.1.0 published.

M4.1 supplies user and payment `*-api`, `*-module` and `*-service` artifacts, plus monolith, hybrid and `none` hosts.
M4.2 exercises hybrid fixed-URL and Consul discovery with Testcontainers. M4.4 supplies the `0.1.0` changelog.
