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
| M0.2 | Module skeleton: `topolith-core`, `topolith-spring-boot`, `topolith-processor`, `topolith-test`, `samples/` reactor; BOM entries | `./mvnw verify` green from a clean checkout |
| M0.3 | ArchUnit layering rules for INV-7, each with a deliberately bad fixture proving it fails | `delivery/61` layering AC |
| M0.4 | CI: PR gate on JDK 21 and 25, AC audit (every `AC-*` has a test named after it, or an owning milestone) | `delivery/60` CI ACs |

**Exit:** the spec covers 0.1; CI green on empty modules; every gate a later milestone needs already runs.

## 2. M1 — Modules and activation

Spec: `api/10`, `api/11`, `api/13`. Model: `architect-review` required for the descriptor format and the activation
mechanism (D-7), since both are public contracts.

| Slice | Contents | Done when |
|---|---|---|
| M1.1 | Descriptor model and module graph in `topolith-core`: parse, `requires`/`optional`, cycles, duplicate ids | `api/10` model ACs |
| M1.2 | `@TopolithModule` and the annotation processor: descriptor generation, `provides` inference, api descriptor | `api/10` processor ACs |
| M1.3 | Mode resolution (D-8), `TopolithImportRegistrar`, `AutoConfigurationImportFilter` (D-7) | `api/11` activation ACs |
| M1.4 | Persistence and migrations: filtered `PersistenceManagedTypes`, per-module repositories, `db/migration/<id>`; module-owned persistence config still works | A `none` module leaves no table, entity or repository |
| M1.5 | Startup validation and `TLnnnn` failures, each tested with `ApplicationContextRunner` | `api/13` ACs |

**Exit:** a host with two modules runs each combination of `internal` and `none`; INV-2 holds by test.

## 3. M2 — External mode

Spec: `api/12`. Model: `architect-review` for the error-translation contract and D-3.

| Slice | Contents | Done when |
|---|---|---|
| M2.1 | HTTP service group per module from `@HttpExchange` contracts; `remote.url` mapped to Boot's client properties; mandatory timeouts | `api/12` client ACs |
| M2.2 | `remote.service-id` through Spring Cloud LoadBalancer when present; fail with the starter to add when not | `api/12` discovery ACs |
| M2.3 | Errors: api exceptions as ProblemDetail and back; `ModuleUnavailableException` for transport failures | Same exception type in `internal` and `external` |
| M2.4 | Exposure: fixed `/<id>` prefix, `expose.enabled`, `SecurityFilterChain` hook; D-2 boundary propagation for internal calls | `api/12` exposure ACs |
| M2.5 | Custom client escape hatch (`ModuleClients.restClientBuilder`) and the separate-HTTP-interface option | `api/12` escape-hatch ACs |

**Exit:** a hybrid host (one module internal, one external against a running service) passes the same tests as the
monolith.

## 4. M3 — Boundaries

Spec: `test/20`, `test/21`.

| Slice | Contents | Done when |
|---|---|---|
| M3.1 | `TopolithArchitecture.verify()`: ArchUnit rules from descriptors (non-api access, undeclared `requires`, cycles, contract types) | `test/20` rule ACs, each with a failing fixture |
| M3.2 | Frozen baseline for adopted repos (D-4) | A baseline only shrinks |
| M3.3 | Startup ownership check (no bean or auto-configuration of a non-internal module, by declaring source) | `test/20` startup ACs |
| M3.4 | `topolith-test` helpers: per-mode test configuration, mock-server wiring for external dependencies | `test/21` ACs |

**Exit:** a deliberate cross-module internal access fails both the test and startup.

## 5. M4 — Reference adopter and 0.1.0

| Slice | Contents | Done when |
|---|---|---|
| M4.1 | `samples/`: user and payment modules (`*-api`, `*-module`, `*-service`), monolith, hybrid and `none` hosts | The three topologies pass the same Payment tests |
| M4.2 | End-to-end tests with Testcontainers: hybrid with a fixed URL and with Consul discovery | CI green |
| M4.3 | Docs: getting started, adopting a legacy repo (RFC 0001 §4.3), configuration reference | Every snippet matches a test |
| M4.4 | Release workflow to Maven Central, CHANGELOG `0.1.0` (Q-1 settled first) | A dry run produces signed artifacts |

**Exit:** 0.1.0 published.
