# 92 — Decisions and open questions

What this file is for: the rationale behind each design choice (`D-n`) and the questions still open for the project
owner (`Q-n`). Rules live in the child specs; this file says why. A later decision replaces an earlier one by marking it
`Superseded by D-n`, never by editing it away.

---

## Decisions

| Id | Decision | Rationale | Source |
|---|---|---|---|
| D-1 | The project is named **Topolith**; the Maven groupId is **`io.github.rey5137`** | Names the idea (topology + monolith). Name check on 2026-11-24: nothing on Maven Central, npm, PyPI or crates.io; no software product or trademark found; `topolith.dev` and `topolith.com` belong to others, so a domain-based groupId isn't available, and the GitHub namespace needs no domain. | RFC 0001 §11; Q-1 (answered) |
| D-2 | An internal contract call runs with **`NOT_SUPPORTED`** at the boundary: the caller's transaction is suspended and the callee's own `@Transactional` decides. A call made inside an active transaction emits a warning metric. | Matches remote behaviour exactly. `REQUIRES_NEW` forces a transaction a remote callee might not open and holds two connections at once (pool starvation). | RFC 0001 §6, §11 |
| D-3 | The contract interface carries **`@HttpExchange`** by default; a separate HTTP interface stays supported | `*-api` needs only `spring-web` annotations, not MVC. Spring MVC maps `@HttpExchange` on a `@RestController` implementation (Framework 6.1+), so one interface serves client and server and they can't drift. | RFC 0001 §5, §11 |
| D-4 | Boundary and data-ownership checks **`fail` by default**; an adopted legacy repo starts from an ArchUnit **frozen baseline** that can only shrink | New code stays clean; old code starts green and only improves. Noisy, unranked violations kill adoption (Packwerk retrospective). | RFC 0001 §8, §11 |
| D-5 | **Native images are out of 0.1.** Mode resolution stays deterministic from configuration so AOT works later; the AOT guard (INV-9) arrives in 0.2. | Not on the critical path; the design already allows it | RFC 0001 §9, §11 |
| D-6 | **No in-JVM isolation of unmodified services** (child contexts). Legacy services join through the adoption checklist and are consumed `external` until then. | A child context sees every parent bean, so legacy auto-config silently uses the host's infrastructure; servlets can't be added after the host server starts; classpath-root resources shadow each other; one classpath forces one library version on all. | RFC 0001 §12 rows 1–2 |
| D-7 | **Inclusion-based activation:** the host scans only its own package; an import selector imports internal modules (D-30; first written as a registrar) and an `AutoConfigurationImportFilter` drops non-internal modules' auto-configurations; migrations live under `db/migration/<id>` | Component-scan exclusion misses auto-configurations, `@Import`, entity scanning and Flyway's merged migration path, breaking INV-2 | RFC 0001 §12 row 3 |
| D-8 | **Modes are explicit in hosts** (INV-3); only a standalone `*-service` defaults its own module to `internal` | A transitive dependency on an implementation JAR must never activate a module silently | RFC 0001 §12 row 6 |
| D-9 | The descriptor is **generated** from `@TopolithModule` on `package-info.java` by an annotation processor; a hand-written one is verified against the bytecode in the build | Hand-written package lists drift on refactoring; `.properties` has no trailing comments | RFC 0001 §12 row 7; Amended by D-19 |
| D-10 | **Baseline:** Java 21, Spring Boot 4.2, Spring Framework 7; Spring Cloud 2026.0 only for discovery | Current GA lines at project start; HTTP service groups and their LoadBalancer configurer need Framework 7 / Boot 4 | —; Amended by D-20 |
| D-11 | **A mode change needs a restart.** If an `EnvironmentChangeEvent` (Spring Cloud `/actuator/refresh`, a Config Server push) changes any `topolith.modules.<id>.mode`, Topolith logs a `TLnnnn` warning that names each changed module with its running and requested modes, and the actuator endpoint reports a restart as pending. The running topology does not change. | INV-9 fixes the topology at context refresh, because a mode decides which bean definitions, auto-configurations, entities and migrations exist, and `@RefreshScope` can only rebuild beans that already exist. Ignoring the change without a word would break INV-4. Switching to `internal` after a restart still needs the implementation JAR on the host's classpath (P-3). | INV-4, INV-9 |
| D-12 | **An `external` module's endpoint settings can change on refresh.** On an `EnvironmentChangeEvent` that changes `remote.url`, timeouts, or retry or circuit-breaker settings, Topolith rebuilds that module's HTTP service group client. Calls already in flight finish on the old client. Changing between `remote.url` and `remote.service-id` changes the module's wiring, so it is treated like a mode change and gets the D-11 warning instead. | These settings aren't topology, so INV-9 allows them to change. Endpoints already move at runtime through `remote.service-id` and discovery, and requiring a restart to repoint a URL or adjust a timeout would gain nothing. | Q-2 (answered); Amended by D-15, D-16 |
| D-13 | Computed restart status; see below | Avoid stale mode status | Q-3 (answered) |
| D-14 | Keep the old client on failed refresh; see below | Preserve service during a failed swap | Q-4 (answered) |
| D-15 | Rebuild on service-id changes; see below | Endpoint changes need no restart | Q-5 (answered) |
| D-16 | Defer resilience policy; see below | Keep 0.1 to mandatory timeouts | Q-6, Q-8 (answered) |
| D-17 | Check cross-module access at startup; see below | Preserve the M3 exit | Q-7 (answered) |
| D-18 | Name the test module `topolith-spring-test` | Keep Spring imports within INV-7 | Q-9 (answered) |
| D-19 | Defer the descriptor verify goal; see below | Retain startup descriptor validation | Q-10 (answered) |
| D-20 | Use optional Cloud integration; see below | Isolate Cloud dependencies | Review 1, 3; owner |
| D-21 | Only internal modules are active for `requires` | External dependencies are remote | Review 6; owner |
| D-22 | Compare API-version majors at readiness; see below | Detect incompatible versions | Review 8; owner |
| D-23 | Warn when Cloud refresh integration is missing | Avoid silently ignored refreshes | Review 4; owner |
| D-24 | Warn on unreachable, non-2xx or unreadable versions; keep readiness up | Availability | Owner |
| D-25 | `<referencing class FQN> -> <referenced class FQN>`; see below | Runtime baseline | Owner |
| D-27 | Strict descriptor format with `format-version`; see below | Typos and drift fail loudly; relaxing later stays compatible | M1.1 architect review; owner |
| D-28 | One unchecked `TopolithException` carrying coded diagnostics; see below | One failure-analyzer path; exact codes in tests; warnings are values | M1.1 architect review; owner |
| D-29 | An API declaration on the api package, verified by the module build; see below | External hosts see only the API descriptor, so it must match the implementation | M1.2 architect review; owner |
| D-30 | Module activation by a non-deferred import selector; see below | Module beans must exist before Boot's conditions run | M1.3 architect pre-read; owner |
| D-31 | Mode resolution from known descriptors and explicit configuration; see below | Typos and silent activation fail loudly | M1.3 architect pre-read; owner |

### D-13 — Computed restart status (Q-3)

Mode-related restart pending clears when every requested mode again equals its running mode. A separate wiring change
can still require restart.

### D-14 — Failed client refresh (Q-4)

Validate endpoint settings before swapping a refreshed client. On invalid settings or rebuild failure retain the old
client, log TL0011 and expose the failure through the actuator; in-flight calls finish on the old client.

### D-15 — Service-id refresh (Q-5)

A change from one `remote.service-id` to another rebuilds the client like a URL change.

### D-16 — Resilience deferred (Q-6, Q-8)

Retry, circuit breakers and idempotency declarations are deferred beyond 0.1; only timeouts are mandatory in 0.1.
This amends the retry and circuit-breaker part of D-12 for 0.1.

### D-17 — Startup boundary enforcement (Q-7)

Startup also rejects a class of one internal module referencing a non-API class of another; D-4's frozen baseline
applies. This preserves the M3 exit.

### D-18 — Spring test module (Q-9)

Name the Spring test-helper module `topolith-spring-test` to keep Spring imports within the INV-7 boundary.

### D-19 — Descriptor verification deferred (Q-10)

Defer `topolith:verify` for hand-written descriptors beyond 0.1; startup still validates descriptors.

### D-20 — Optional Cloud integration (review 1, 3)

Spring Cloud 2026.0 is used only for discovery and refresh in optional `topolith-spring-cloud`. Without Spring Cloud
on the classpath there is no refresh handling. This amends D-10.

### D-21 — Active dependencies (review 6)

Only internal modules are active for `requires` checks; external modules' dependencies are met remotely.

### D-22 — Version readiness (review 8)

Readiness compares host API descriptor majors with the internal module descriptor or, for external mode, the
provider's version endpoint under its exposed module prefix. A mismatch warns by default.

### D-23 — Missing refresh integration (review 4)

`topolith-spring-boot` detects Spring Cloud context classes by name, without importing them. If they are present and
`topolith-spring-cloud` is absent, it logs warning TL0014 at startup directing the user to add that module for refresh.

### D-24 — Version endpoint failures (review 5)

The provider exposes its API version at `/<id>/.topolith/api-version`. If that endpoint is unreachable, returns any
non-2xx response, or returns a 2xx response without a readable version, the host logs warning TL0015 and readiness
remains up.

### D-25 — Packaged startup baseline (review 6)

M3.2 baseline tooling generates `META-INF/topolith/baseline/<id>.txt` in the referencing module from the frozen
ArchUnit store. Each violation is a line formatted `<referencing class FQN> -> <referenced class FQN>`; blank lines and
lines starting with `#` are ignored. The startup boundary check reads the packaged resource to recognize violations.

### D-26 — Committed slices need named tests (M0 gate)

A criterion's owning slice excuses a missing named test only until that slice is committed. The audit reads committed
slices from every parenthesised group in the commit subjects of the checked-out history (`(Mn.m)`, `(D-n, Mn.m, …)`),
so CI checks out full history; the audit fails on a shallow clone or unreadable history, and a repository with no
commits has no committed slices. A subject that names a slice before it is built fails that slice's untested criteria,
so planning commits name slices outside parentheses. A squash merge whose subject names no slice leaves its criteria
pending.

### D-27 — Descriptor format (M1.1)

Descriptors are UTF-8 `.properties` files. Duplicate keys, unknown keys, a missing required key or a malformed value
fail with TL0013. Lists are comma-separated, with entries trimmed, blank entries dropped and duplicates rejected. A
missing or empty `requires`, `optional` or `provides` means none. Required in both kinds: `format-version=1`, `id`
(must match the file name, `[a-z][a-z0-9]*(-[a-z0-9]+)*`, at most 63 characters), a non-empty `api-packages`, and an
`api-version` with a numeric major. `api-packages` lists exact packages, not their subpackages. `provides` entries lie
in an api package. A module may not list itself, or list one id in both `requires` and `optional`. A module descriptor also requires `package` (M1.3, D-30): a valid
package that is not one of its own `api-packages`. An API descriptor has no `package`, `requires` or `optional` key. The core parser raises TL0013; M1.5 makes startup fail with it. Starting strict
is deliberate: relaxing a rule before 1.0 stays compatible, tightening one breaks hand-written descriptors.

### D-28 — Core failure reporting (M1.1)

`topolith-core` throws the unchecked `TopolithException`, carrying one or more diagnostics (each with a `TopolithCode`
enum value, a description and an action). Validation reports every problem in a stage (parse, then identity, then
graph) and stops after the first stage that fails. Warnings are returned as values and never thrown, and core never
logs. `ModuleUnavailableException` does not extend `TopolithException`.

### D-29 — API declaration and `provides` verification (M1.2)

`@TopolithModule` (`topolith-core`, package `io.github.rey5137.topolith.annotation`, CLASS retention, String-only
members `id`, `api`, `requires`, `optional`) on a package that is in its own `api` list is an **API declaration** and
emits `apis/<id>.properties`; anywhere else it is a **module declaration** and emits `modules/<id>.properties`. An API
declaration may not set `requires` or `optional`, and each of its `api` packages must hold a type in the compilation.
Its `provides` are the public, non-sealed, non-annotation interfaces in the api packages, keeping only the
most-derived ones. A module declaration infers `provides` from the concrete classes under its package that implement
those interfaces. When the api packages come from the classpath, it reads the API declaration from the package-info
class of an api package (CLASS retention, so JARs and class directories alike), fails if there is none or its `id` or
`api` differ, and recomputes the API's `provides` from the classpath types, failing if they differ from the inferred
ones; this does not prove the API artifact carries its descriptor file. When every api package is in the same
compilation (a single-artifact module) it compares in the same way and emits both descriptors. javac's
`Filer.getResource` cannot open JARs, so the descriptor file is not the verification source. An api package holding
both compiled and classpath types, or a classpath declaration, fails as split.
`api-version` comes from the required
processor option `-Atopolith.apiVersion`, and its absence is a compile error citing TL0013. One declaration per
compilation.

Rationale: an `external` or `none` host has only the API descriptor, so it must equal what the module implements
(INV-6, INV-4, P-6). Rejected: every interface as `provides` (an HTTP proxy with no internal bean, a parity break),
processor options in the api pom (test-compile duplicates, stringly typed), generating it in the module build
(the api JAR is already built). Accepted strictness: a consumer-implemented SPI interface or a contract implemented
only by a lambda or `@Bean` fails the build (Q-11).

### D-30 — Module activation (M1.3)

`TopolithAutoConfiguration`, ordered first, imports a non-deferred `TopolithModuleImportSelector`. The selector returns
the internal modules' scan candidates: their `package`, less every known api package, with Boot's `TypeExcludeFilter`
and `AutoConfigurationExcludeFilter` applied. Module descriptors carry a required `package` (format-version 1,
pre-release; API descriptors have none). An auto-configuration is owned by a module if its name is under the module's
package, or if an `AutoConfiguration.imports` file lists it and the imports file or the class (found as a resource,
not loaded) is in the classpath root that holds the module's sole descriptor; two roots naming different modules own
nothing. Module
packages never nest (TL0017). A host component scan covering a non-internal module's package fails with TL0007.
Imported classes get fully qualified default bean names (explicit `@Component("x")` names are kept).

Rationale: classes imported by a selector are parsed before later auto-configurations, so Boot's conditions see module
beans. Rejected: an `ImportBeanDefinitionRegistrar` (its imports are parsed in a second round, after Boot's
`@ConditionalOnMissingBean` checks, which breaks AC-MODE-12), an initializer-registered
`BeanDefinitionRegistryPostProcessor` (it also runs in the Spring Cloud bootstrap context and in no
`ApplicationContextRunner`), package-only ownership (an auto-configuration outside the root would leak), and longest
prefix ownership. This replaces the registrar wording of D-7 and RFC 0001 §3.2.

### D-31 — Mode resolution (M1.3)

A module is known when a module descriptor or an API descriptor carries its id. Each known module declares
`topolith.modules.<id>.mode`, read through `Binder`, trimmed and case-insensitive. A missing or invalid mode is TL0001.
A mode for an id that is not on the classpath is TL0016, or TL0002 when it is `internal`; `internal` for a module known
only through its API descriptor is TL0002. `topolith.standalone=<id>` defaults only that module to `internal` and needs
its module descriptor (TL0002). Topology is resolved once, in the order parse (TL0013), identity (TL0006, TL0017), graph
(TL0008), modes (TL0001, TL0016, TL0002), requirements (TL0004), and cached as the `topolithTopology` bean. TL0004
checks internal modules against `internal` plus `external` modules (D-21).

Rejected: inferring the standalone service from the classpath or the artifact name, binding straight to an enum, and
ignoring unknown ids. Known limit: an environment variable drops the hyphens of a map key, so a hyphenated module id
set only by environment variable fails loudly (TL0001 and TL0016).

## Open questions

| Id | Status | Question |
|---|---|---|
| Q-3 | Answered (D-13) | If a refreshed mode returns to its running value, when is restart-pending status cleared? |
| Q-4 | Answered (D-14) | How should refresh handle invalid endpoints or failed rebuilds, including in-flight calls? |
| Q-5 | Answered (D-15) | How should changing one `remote.service-id` to another on refresh affect the running client? |
| Q-6 | Answered (D-16) | Which retry and circuit-breaker policies and defaults apply in 0.1? |
| Q-7 | Answered (D-17) | Does M3 require detecting internal cross-module access at startup? |
| Q-8 | Answered (D-16) | How is a contract call declared or determined to be idempotent for retry? |
| Q-9 | Answered (D-18) | How can Spring-based test helpers satisfy INV-7's Spring import restriction? |
| Q-10 | Answered (D-19) | Which slice owns the Maven plugin's hand-written descriptor verification goal? |
| Q-11 | Open | Should `@TopolithModule` gain an explicit `provides` attribute for consumer-implemented SPI interfaces and lambda-only contracts, which D-29 rejects? |
| Q-12 | Open | Should startup fail when a host's API JAR `provides` differs from the module descriptor (M1.5)? A new TL code needs reference/90 first. |
| Q-13 | Open | Who owns the `META-INF/spring.factories` and `*ManagementContextConfiguration.imports` entries of a non-internal module's JAR (M3.3), which no `AutoConfigurationImportFilter` sees, and the guard for Topolith's auto-configuration being excluded while a module is internal? |
