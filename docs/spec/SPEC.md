# Topolith — Specification Index

**Topolith** lets a Spring Boot business module run **in-process** (`internal`), **remote** (`external`) or **not at
all** (`none`), chosen per host in configuration, without changing the module's code; `loopback` runs it in-process
behind real HTTP, to test that it behaves the same once extracted. Modules come from one multi-module
repo or from one-service-per-repo legacy repos, and any set of them can be bundled into one image or split back out.
Topolith enforces module boundaries itself; Spring Modulith is an optional add-on.

- **Implementation:** Java 21, Maven multi-module, Spring Boot 4.2 / Spring Framework 7.
- **Optional integrations:** Spring Cloud LoadBalancer (discovery), Spring Modulith (verification, events, docs).
- **Open source:** Apache-2.0, published to Maven Central as `io.github.rey5137:topolith-*` (D-1).
- **Design record:** [`rfc/0001-architecture.md`](../../rfc/0001-architecture.md). The spec states the rules; the RFC
  states the reasoning and the alternatives that lost.

---

## 1. How to use this spec

`SPEC.md` is the only file meant to be read every time. Everything else is loaded on demand. Each child file states
what it is for in its first five lines, so you can route without reading the body.

### Routing table

Child files are written in milestone M0 (`docs/plan/mvp-plan.md` §1, slice M0.1).

| If you are working on… | Read |
|---|---|
| Anything at all | this file (invariants, principles) |
| `@TopolithModule`, the descriptor format, `provides` inference, api descriptors | `api/10-descriptors.md` |
| Modes, activation, persistence and migration filtering | `api/11-modes-activation.md` |
| External HTTP groups, endpoints, timeouts, errors, exposure | `api/12-external.md` |
| Startup validation, the actuator endpoint, `TLnnnn` diagnostics | `api/13-validation-actuator.md` |
| ArchUnit boundary rules, the frozen baseline, the startup ownership check | `test/20-boundaries.md` |
| `topolith-spring-test`: mode test configuration, test helpers | `test/21-test-helpers.md` |
| Test layers, CI gates, the AC audit | `delivery/60-quality-ci.md` |
| Module layout, dependency rules, releases, publishing, license, RFCs | `delivery/61-build-release.md` |
| Milestones, release scope, exclusions | `delivery/62-roadmap.md` |
| `TLnnnn` codes, exception catalog, glossary | `reference/90-catalog.md` |
| Why a design choice was made; open questions for the owner | `reference/92-decisions-questions.md` |

### Identifier scheme

| Prefix | Meaning | Defined in |
|---|---|---|
| `INV-n` | architectural invariant | this file |
| `P-n` | design principle | this file |
| `D-n` | design decision | `reference/92` |
| `Q-n` | open question for the project owner | `reference/92` |
| `R-<AREA>-nn` | binding rule | the owning child file |
| `AC-<AREA>-nn` | acceptance criterion, covered by a test named after it | the owning child file |
| `TLnnnn` | diagnostic code (startup failure, build-time check, warning) | `reference/90` |

Ids are stable: never renumber; retire an id by marking it `Retired`.

---

## 2. Invariants

Breaking an invariant is an architecture decision that needs an accepted RFC, never a patch.

| Id | Invariant |
|---|---|
| INV-1 | **Business code needs no Topolith type.** The only Topolith API a module may carry is the optional `@TopolithModule` on `package-info.java` and the types of `io.github.rey5137.topolith.contract`. |
| INV-2 | **A module that is neither `internal` nor `loopback` (D-75) contributes nothing to the host:** no bean, auto-configuration, entity, repository, migration, route, listener or scheduled task. |
| INV-3 | **Explicit topology.** A host states the mode of every module it knows; only a standalone `*-service` may default its own module to `internal` (D-8). |
| INV-4 | **Loud over silent.** An unsatisfiable or ambiguous topology fails startup with a `TLnnnn` code and a fix-it message. Nothing falls back, picks a winner (`@Primary`) or drops a call or event silently. |
| INV-5 | **Contracts only.** A module calls another only through a contract type that the callee `provides`. |
| INV-6 | **Mode parity.** A call to a contract has the same observable semantics whichever mode serves it: no shared transaction (D-2), arguments and results by value, the same exception types (declared contract exceptions and `ModuleUnavailableException`, D-55), the same observation. |
| INV-7 | **One-way module dependencies.** `topolith-core` imports only the JDK; only `topolith-spring-*` modules import Spring; Spring Cloud and Spring Modulith are imported only by optional modules. Checked by ArchUnit. |
| INV-8 | **Standard Spring extension points only.** No custom container, RPC protocol, message broker or class loader. |
| INV-9 | **The topology is fixed at context refresh** (at build time for AOT and native images). There is no runtime mode change. |
| INV-10 | **A `TLnnnn` code's meaning is never reused.** A code is added to `reference/90` before the code that raises it. |

## 3. Principles

| Id | Principle |
|---|---|
| P-1 | A module is metadata, not a base class. |
| P-2 | Every convention is a default that a module or host can override with its own `@Configuration`, except parity (INV-6), which can only be relaxed to `warn` explicitly. |
| P-3 | Classpath decides what is possible; configuration decides what is active. |
| P-4 | Strict for new code, a ratchet for old code: boundary checks `fail`, and an adopted repo starts from a frozen baseline that can only shrink (D-4). |
| P-5 | Build on Spring and Spring Modulith; never duplicate what they already do well. |
| P-6 | Generated over hand-written metadata, and verified in the build when it must be hand-written (D-9). |

## 4. Decisions index

`reference/92` holds every `D-n` with its rationale. The founding decisions D-1..D-10 come from RFC 0001.
