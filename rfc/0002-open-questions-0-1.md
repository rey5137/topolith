# RFC 0002 — Answering the open questions before 0.1.0

- **Status:** accepted
- **Affects:** Q-11, Q-13, Q-16 to Q-19, Q-21, Q-22, Q-24, Q-26 to Q-31,
  Q-32 and Q-33 (raised in review)
  (`docs/spec/reference/92-decisions-questions.md`); D-27, D-29, D-32, D-36, D-43, D-61, D-79, D-87, D-90, D-93,
  D-95, D-105, D-109; P-2; R-MODE-04, R-MODE-06, R-EXT-04, R-EXT-12, R-EXT-13, R-TEST-04; TL0007, TL0020, TL0024, TL0027
- **Decisions:** D-110 through D-126 (`docs/spec/reference/92-decisions-questions.md`)
- **Discussion:** none (owner decision, M8.1)

## Summary

Fifteen questions are still open in `reference/92`. The owner's direction is to answer all of them for 0.1.0 instead
of deferring them. Twelve are built in M8.2 to M8.8. Q-27 (events, which arrive in 0.3) and Q-31 (a second
circuit-breaker implementation) are decided now and built later. Each section below gives the problem, two or three
options and a recommendation. The owner picks one option per question, and the picks become `D-n` rows and `AC-*`
criteria in sub-step 2. "New TL code" means a code that the building slice adds with its `TopolithCode` constant
(INV-10). New public types are `@Incubating` unless a section says otherwise.

## Decision

The owner accepted every recommendation on 2026-11-27:

| Question | Pick | Decision | Built in |
|---|---|---|---|
| Q-11 | C: `@ConsumerImplemented` marker, `provides` on the module declaration | D-110 | M8.3 |
| Q-13 | A: import listener, TL0042 | D-111 | M8.2 |
| Q-16 | B: Topolith runs the migrations on the module's DataSource | D-112 | M8.3 |
| Q-17 | B: R2DBC, MongoDB and MyBatis registered per module | D-113 | M8.4 |
| Q-18 | A: host mapper-location check in every mode, TL0043 | D-114 | M8.2 |
| Q-19 | A: `api = {}` on a module declaration | D-115 | M8.2 |
| Q-21 | B: checked exceptions; the payload (C) stays open for a later release | D-116 | M8.5 |
| Q-22 | B: `ModuleUnavailableException` for 401, 403, 404, 405, 415; three JDK exceptions declared | D-117 | M8.5 |
| Q-24 | B: test helper and close guard | D-118 | M8.7 |
| Q-26 | C: proxy on by default, `topolith.test.parity=fail\|warn`, TL0045 | D-119 | M8.7 |
| Q-27 | A: loopback events go through the adapter | D-120 | decision only (0.3) |
| Q-28 | A with (ii): session-token relay, runtime warning TL0046 | D-121 | M8.8 |
| Q-29 | C: `ContextCarrier`, plus tracer-baggage docs | D-122 | M8.8 |
| Q-30 | A: `exception` is the parity key | D-123 | M8.7 |
| Q-31 | A: adapter stays internal | D-124 | decision only |
| Q-32 | Topolith registers routes for a listed contract's bean, to the architect's design | D-125 | M8.6 |
| Q-33 | Startup route-coverage check for exposed and loopback modules, TL0044 widened | D-126 | M8.3 |

Details settled while writing the rules and in review:

- Q-11: a missing bean for a listed contract is TL0044. Both sides drop `@ConsumerImplemented` interfaces before
  keeping the most-derived. A listed contract's bean is owned by its definition, so the transaction boundary and the
  observation apply whatever its class. In M8.3 a listed contract has no route, and a module that lists contracts
  fails with TL0044 when it is `loopback` or exposed. The fix is a module `@RestController`, with D-49 only for a
  contract without `@HttpExchange`.
- Q-32 (raised in review): from M8.6, Topolith registers routes for a listed contract's bean, which relaxes that
  TL0044 (D-125).
  - Routes come from Spring's own mapping rules, with ownership decided by a handler registry.
  - A method without `@HttpExchange` fails with TL0026, and a path clash with TL0005.
  - A delegating body handler writes the result, and each call records one observation.
- Q-33 (raised in review): in an exposed or loopback module, every provided contract method needs a module route at
  startup, or startup fails with TL0044 (D-126, M8.3).
- Q-21: the checked class named in `throws` must itself be in an api package. The ancestor rule applies only to the
  subclass actually thrown.
- Q-22: only a status the module's handler did not produce becomes `ModuleUnavailableException`. The provider marks
  its handlers' responses with a `Topolith-Module` header, so a marked 4xx stays undeclared, as in internal mode.
- Q-26: a missing JSON converter fails a test context only when an internal module provides a contract to wrap.
- Q-28: the session relay uses a manager Topolith builds from the host's single client-registration and
  authorized-client repositories, never a host manager bean (INV-4). With none of either, session relay is off. With
  several, startup fails with TL0047. With no current request, nothing is sent.
- Q-29: the cap of 16 counts MDC keys and carriers together.

## Motivation

0.1.0 is the first release. Anything strict that ships in it can be relaxed later without breaking anyone (D-27). A
gap left open now either becomes a default that later releases have to break, or stays a documented hole that
adopters find in production. Several of the questions are parity gaps (INV-6) or `none`-module leaks (INV-2), which
the project's own invariants don't allow to stay open.

## Design

### Q-11 — Consumer-implemented SPIs and lambda-only contracts (M8.3)

**Problem.** D-29 infers an API's `provides` from every public, non-sealed interface in its api packages, and a
module's from its concrete implementing classes. Two cases fail the build: an SPI interface that other modules
implement, and a contract that only a `@Bean` lambda or factory method provides (no class for the processor to see).

- **A. `provides` attribute.** `String[] provides() default {}` on `@TopolithModule`. On an API declaration it replaces
  the inference: every entry must be a public interface in the api packages. On a module declaration it adds contracts
  that have no concrete class. The descriptor format doesn't change. Cost: verbose, and an API that forgets an entry
  silently drops a contract, which the module-side comparison then has to catch. About 150 lines in the processor.
- **B. Marker annotation.** `@ConsumerImplemented` (RUNTIME, TYPE) in `io.github.rey5137.topolith.contract`, so INV-1
  still holds. A marked interface is left out of `provides` on both sides, and an internal module's class that
  implements it is not a provider. This fixes SPIs only; lambda contracts still fail. About 100 lines.
- **C. Both, each for its own case.** B's marker for SPIs, plus `provides` on the **module** declaration only, listing
  contracts provided by `@Bean` methods. Each listed entry must be in the API's inferred `provides` and must not also
  have a concrete class. At startup an internal module must have exactly one bean for each listed contract: several
  fail with TL0005, and none needs a new TL code. The D-47 boundary proxy must fall back to an interface proxy for a
  lambda, whose class is final. About 250 lines.

Every option adds public surface (`@Incubating` member or annotation). None breaks an `INV-*`. Each can be extended
later without breaking anyone.

**Recommendation: C.** The marker describes the SPI on the type itself, so the API and every module read the same
fact without a list that can drift. The explicit list is needed only where the processor truly can't see the
provider.

### Q-13 — Topolith's auto-configuration excluded while a module is internal (M8.2)

**Problem.** If the host excludes `TopolithAutoConfiguration` (`spring.autoconfigure.exclude` or
`@SpringBootApplication(exclude = …)`), no module is imported and no startup check runs. Meanwhile the
`spring.factories` import filter still runs. The symptom is a missing bean, or a missing check, with no TL code. D-61
left this open.

- **A. Import listener.** An `AutoConfigurationImportListener` registered in `spring.factories`. Boot passes it the
  exclusions of every `@EnableAutoConfiguration` import. If any `io.github.rey5137.topolith.*` auto-configuration is
  excluded while the resolved topology has an `internal` or `loopback` module, startup fails with a new TL code that
  names the exclusion and its source key. No public surface. About 60 lines.
- **B. Post-refresh check.** A `spring.factories` `ApplicationListener` that fails when modules are known but the
  topology bean is missing. This also catches hosts that never import auto-configuration, but it fails every test
  slice (`@WebMvcTest`, `@DataJpaTest`) that leaves Topolith out on purpose.
- **C. Documentation only.** Close Q-13 as a known limit.

**Recommendation: A.** It reacts only to an explicit exclusion, so it never misfires on a slice test.

### Q-16 — A Pattern A module's migrations on its own DataSource (M8.3)

**Problem.** A module that contributes its own (non-default-candidate) DataSource must also declare its own Flyway,
`FlywayMigrationInitializer` or `SpringLiquibase`, or startup fails with TL0020 (D-32, Pattern A). Topolith's module
runners always use the host's DataSource (`ModuleMigrations`, `db/modules/<id>`).

- **A. Status quo.** The module declares its own runner. Document it as the Pattern A rule.
- **B. Topolith runs them on the module's DataSource.** A Pattern A module that has scripts under `db/modules/<id>`,
  exactly one DataSource bean of its own and no runner of its own gets Topolith's module runner bound to that
  DataSource, with the same per-module tracking tables and limits. A runner declared by the module still owns the
  migrations, which is an explicit override (P-2). Several module DataSources and no runner fail with TL0020. INV-2
  is unchanged (only internal and loopback modules get runners). About 150 lines.
- **C. Choose by property.** `topolith.modules.<id>.migrations.data-source=<bean name>` selects the DataSource. This
  adds a key, and with only one module DataSource it adds nothing B doesn't already do.

**Recommendation: B.** It removes the one piece of boilerplate Pattern A still needs. Topolith acts only when the
choice is unambiguous, so nothing is picked silently (INV-4).

### Q-17 — R2DBC, MongoDB and MyBatis registered per module (M8.4)

**Problem.** TL0018 stops Boot's default repository scan from reaching module packages. Topolith registers JPA and
JDBC repositories per internal module to make up for that (D-36, AC-MODE-33..36). R2DBC and MongoDB repositories and
MyBatis mappers instead fail with TL0024 until the module adds its own `@Enable…Repositories` or `@MapperScan`.

- **A. Status quo.** TL0024 with the fix-it message. Document it.
- **B. Register all three.** One auto-configuration per store, mirroring the JDBC one: scan only internal and
  loopback module packages, honour a module's own registration (as AC-MODE-35 does), and use
  `@ConditionalOnClass` on each store, so a host without the store loads nothing. MyBatis uses `MapperScannerConfigurer`
  bound to the host's single `SqlSessionFactory` (several without a module-owned one fail with TL0024's fix). No public
  surface. About 120 lines per store, plus registration-only tests (bean definitions, no server).
- **C. MongoDB and MyBatis only.** R2DBC stays at TL0024, because Topolith's migrations and repository checks are
  JDBC-based.

**Recommendation: B.** The three stores follow the same pattern as JDBC, and leaving any one behind keeps TL0024 as
a trap for the adopters who use it. Tests stay at the bean-definition level, so CI doesn't need a database server.

### Q-18 — A host's `mybatis.mapper-locations` reaching a `none` module's XML (M8.2)

**Problem.** A host pattern such as `classpath*:mapper/**/*.xml` loads mapper XML from every module JAR, a `none`
module's included (INV-2). Nothing checks it today. TL0020 already rejects host migration locations that reach module
scripts.

- **A. Host-location check in every mode.** Resolve the host's `mybatis.mapper-locations` at startup. A resource owned
  by any module (D-61 ownership: under the module's package directory, or in a JAR that holds only that module's
  descriptor) fails with a new TL code naming the location and resource, whatever the module's mode. A host that
  starts in one topology then starts in all of them, as with TL0018 and TL0020. Module XML lives next to its mapper
  interface, where MyBatis loads it by convention. Built after Q-17's MyBatis registration if B is picked there.
  About 80 lines.
- **B. Fail only for non-internal modules (TL0007).** No new code, but the result depends on the topology.
- **C. Documentation only.** This leaves an INV-2 leak in place, so it is not recommended.

**Recommendation: A.** It is the TL0020 rule applied to MyBatis, so the failure doesn't depend on topology.

### Q-19 — A module with no contracts (M8.2)

**Problem.** `@TopolithModule.api()` is required, and both descriptor kinds require a non-empty `api-packages` (D-27,
D-29). A listener-only or job-only module therefore needs an API artifact holding a placeholder type.

- **A. Allow `api = {}` on a module declaration.** `api()` gets `default {}`. A module declaration without api packages
  writes no `api-packages` key, `provides` is empty and no API descriptor is emitted. `api-version` is still required
  (it versions events from 0.3). `external` or `loopback` for such a module fails with TL0003 (nothing to call). An API
  declaration still needs at least one package. Relaxing the descriptor rule keeps compatibility (D-27). About 80
  lines.
- **B. Status quo with a documented placeholder package.**
- **C. A separate `@TopolithComponentModule` annotation.** This adds public surface and a second declaration path.

**Recommendation: A.** It is a strict-to-relaxed change, which D-27 already says stays compatible, and it removes an
artifact that exists only to satisfy the format.

### Q-21 — Checked exceptions and exception payload across HTTP (M8.5)

**Problem.** TL0027 fails a contract method that declares a checked exception. A declared exception crosses HTTP as
its class name and message only, and its cause never crosses (D-43, R-EXT-04).

- **A. Status quo.** Document it as a contract-design rule.
- **B. Allow checked exceptions declared in the method's `throws` clause.** The class must be in an api package and
  must meet the same constructor and `@ResponseStatus` rules. The caller's advice rebuilds and throws it (the proxy
  method declares it, so no `UndeclaredThrowableException`). TL0027 keeps its other uses. The by-value proxy and the
  `exception` observation key follow R-EXT-04's declared class. No new surface. About 100 lines.
- **C. Payload.** A declared exception's own bean properties (getters declared below `Throwable`) are written into the
  problem as `payload` with the module's JSON mapper and read back through a `@JsonCreator` or matching constructor.
  The cause chain never crosses, because it would leak provider internals. This adds a rule, and some exceptions with
  odd properties fail TL0027 at startup. About 250 lines.

None breaks an `INV-*`. C makes parity stronger (the caller sees the same fields). Both B and C can be added later
without breaking anyone.

**Recommendation: B for 0.1.0.** C is optional: it is compatible to add later, and its mapper rules are the least
settled part of this RFC.

### Q-22 — 4xx without a Topolith problem, and JDK exceptions as declared (M8.5)

**Problem.** A 404, 405 or 415 (route missing, version skew, exposure off) or a 401 or 403 (security, or a
`TenantContext` rejection) without a Topolith problem gives the caller R-EXT-04's unspecified exception. An
`IllegalArgumentException` that crosses in internal mode is undeclared in external mode, so its type differs between
modes (INV-6).

- **A. Status quo.**
- **B. One topology failure type.** All five statuses without a problem become `ModuleUnavailableException`, with
  new fixed kinds `status 404|405|415|401|403`. Their outcome is OTHER: no retry and no breaker failure (D-105
  unchanged). R-EXT-12's tenant rejection becomes this exception too. `IllegalArgumentException`,
  `IllegalStateException` and `UnsupportedOperationException` (and subclasses, as their nearest listed class) count
  as declared and cross with their message. Trade-off: a library's message such as an enum lookup reaches the caller,
  but the internal caller already sees it. No new type. About 120 lines.
- **C. Split by cause.** 404, 405 and 415 become `ModuleUnavailableException`. 401 and 403 become a new final
  `ModuleAccessDeniedException` in `io.github.rey5137.topolith.contract`, declared on every contract like
  `ModuleUnavailableException` (public, `@Incubating`). The JDK list is as in B. About 180 lines.

**Recommendation: B.** The statuses are failures that only the topology adds, and internal mode never raises them,
so a single type the caller already handles keeps the contract small. C is the choice if callers need to tell a
denial apart from a missing route.

### Q-24 — A supported wait for the remote version check (M8.7)

**Problem.** TL0015 and TL0023 come from a daemon thread started at `ApplicationReadyEvent` (`VersionReadiness`). A
test that asserts on them races the thread, and a cached context can log after it has closed. `await` is
package-private.

- **A. Boot method.** A public `@Incubating` `TopolithVersionChecks.await(Duration)` bean in `topolith-spring-boot`.
  This adds production surface for a test-only need.
- **B. Test helper plus close guard.** `TopolithTestSupport.awaitVersionChecks(ApplicationContext, Duration)` (public,
  `@Incubating`, `topolith-spring-test`) reaches the check through the D-109 internal seam. Separately, context close
  stops the check thread and drops any log line after close, so no TL0015 or TL0023 outlives its context. About 70
  lines.
- **C. Documentation only.** Use a per-context log capture with a polling assertion. The close race stays.

**Recommendation: B.** The wait is a testing concern, and the close guard fixes the race in production too.

### Q-26 — By-value parity: opt-in, ratchet or default (M8.7)

**Problem.** The by-value proxy is opt-in per test (`@TopolithByValue`, D-79, R-TEST-04). P-2 says parity is on
unless it is explicitly relaxed to `warn`, but no `warn` setting is specified.

- **A. Keep opt-in.** Amend P-2 to say that test-time parity is opt-in.
- **B. P-4-style ratchet.** Opt-in in 0.1, default `warn` in 0.2, default `fail` in 0.3. Each step changes users'
  test outcomes.
- **C. On by default now.** With `topolith-spring-test` present, every Spring test context wraps each internal
  module's contracts. `topolith.test.parity` is `fail` (default) or `warn`, read from the test context's `Environment`
  (`@TestPropertySource`, `@SpringBootTest(properties)`, test `application.properties`). There is no `off` (P-2). A
  `warn` run returns the provider's result as it is and logs each violation once as a new TL warning naming the
  module, method and JSON path, never the value. `@TopolithByValue` remains only to narrow the module ids. With no
  JSON converter, a context that has internal modules fails as today, with a fix naming Jackson. INV-2 holds: only
  internal modules are wrapped. About 150 lines, plus updates to this repo's own tests.

**Recommendation: C.** 0.1.0 has no users to ratchet, starting strict is compatible (D-27), and the release valve is
exactly the `warn` that P-2 promises.

### Q-27 — Loopback and mode-aware events (decision only; built with events in 0.3)

**Problem.** Loopback checks contract-call parity only (R-MODE-06). RFC 0001 §7 sends an event that crosses a process
boundary through an externalization adapter, and fails startup when there is none.

- **A. Treat loopback as external for events.** A loopback module's cross-module events go out and come back through
  the adapter (serialization included). With no adapter, startup fails with the same code and message as for an
  external module.
- **B. Always fail.** Loopback events are rejected even when an adapter exists. Loopback becomes useless for
  event-driven modules.
- **C. Deliver in-process.** This hides serialization and ordering differences, which is the parity gap loopback
  exists to expose.

**Recommendation: A.** Loopback means "exercise the remote path in one process" for every crossing, events included.

### Q-28 — Relaying an OAuth2 login session's token; loopback hosts that can't authenticate it (M8.8)

**Problem.** Relay sends only an `AbstractOAuth2TokenAuthenticationToken`'s bearer (D-93). A session login
(`OAuth2AuthenticationToken`) sends nothing. A provider whose security chain can't authenticate a relayed token runs
the call as anonymous, and that is a documented gap (D-95). D-95 rejected a startup heuristic.

- **A. Relay the authorized client's token.** With `spring-security-oauth2-client` present, `relay` also resolves the
  session's `OAuth2AuthorizedClient` through the host's single `OAuth2AuthorizedClientManager` (refreshing an expired
  token as Spring does) and sends its access token. If nothing resolves, nothing is sent (as today). No new key.
  About 100 lines.
- **B. Status quo for sessions.**
- **Gap detection (either option).** (i) A startup warning based on decoder beans and properties. D-95 rejected this
  because the DSL configures decoders inline. (ii) A runtime warning: when a request to a module route carries
  `Authorization: Bearer` but reaches the handler unauthenticated or anonymous, log a new TL warning once per module.
  This is exact, with no false positives, and it covers loopback and external providers alike. About 50 lines.

**Recommendation: A with (ii).** Session-based UIs are the common caller. The runtime warning reports only what
actually happened, which answers D-95's objection.

### Q-29 — Propagating more context than MDC keys and a tenant (M8.8)

**Problem.** Only the listed MDC keys and one tenant cross (D-90, D-91). Micrometer `ThreadLocalAccessor` values are
`Object`s with no string form. W3C baggage without a tracer has no source on the calling thread.

- **A. Listed `ThreadLocalAccessor` keys.** These cross as strings and are restored by `setValue(String)`. A value
  that isn't a String fails at the call. The typing problem appears only at run time.
- **B. `baggage` header without a tracer.** Topolith would need its own baggage store, which duplicates Micrometer
  Tracing. With a tracer, Micrometer's own `management.tracing.baggage.remote-fields` already crosses on the
  instrumented client. Document that path instead.
- **C. Public `ContextCarrier` SPI.** `ContextCarrier` (public, `@Incubating`, `io.github.rey5137.topolith.spring.boot`,
  host beans only, as `TenantContext` is, so INV-1 holds): `String name()`, `String current()`,
  `Scope open(String value)`. Each value crosses as `Topolith-Context-<name>` under D-90's limits (1024 encoded
  characters, at most 16 MDC keys and carriers together, TL0034 and TL0035 extended). An adapter to A can be built
  on it later. About 150 lines.

**Recommendation: C, and document B's tracer path.** C is string-typed by construction, and it is the same shape as
`TenantContext`.

### Q-30 — Micrometer's `error` tag across modes (M8.7)

**Problem.** Micrometer's meter handler tags `error` with the simple class name of the observation's error. For a
declared exception's subclass outside the api packages, or for an undeclared exception, that class differs between
modes. Topolith's own `exception` key is already mode-independent (D-87).

- **A. `exception` is the parity contract.** Document `error` as Micrometer's raw class and add an AC that `exception`
  agrees across modes for both cases. No code change apart from the test.
- **B. Record a substitute error.** In internal mode, set the observation's error to the class external mode would
  rebuild. Spans would then carry a fabricated exception, and the undeclared case still can't match.
- **C. Replace the host's meter handler.** This picks a winner over the host's handler (INV-4), so it is **not
  recommended**.

**Recommendation: A.** Only `exception` can match in every case.

### Q-31 — Circuit-breaker adapter: internal or SPI (decision only)

**Problem.** In 0.1, `Resilience4jAdapter` (package-private, `topolith-spring-cloud`) is the only path to a breaker.
Exactly one supported `CircuitBreakerFactory` bean is required, and any other count fails (D-105, D-109).

- **A. Stay internal.** Each supported implementation is a package-private adapter added in `topolith-spring-cloud`.
  When a second one ships, a host with several factory beans selects one with
  `topolith.resilience.circuit-breaker.factory=<bean name>`. Without that key it still fails.
- **B. Public `@Incubating` adapter SPI.** Third parties could plug in breakers, but the SPI would have to expose the
  ownership checks and the D-105 outcome recording, which are still moving.
- **C. Use Spring Cloud's `CircuitBreaker` API alone.** This loses Topolith's ownership of breaker `<id>` and its
  outcome classification.

**Recommendation: A.** Make it public only once two implementations show what the SPI needs.

## Compatibility

0.1.0 is unreleased, so no option breaks a released artifact. The recommended options relax rules (Q-11, Q-19, Q-21),
add checks to paths that are unchecked today (Q-13, Q-18, Q-28), or turn on a default that can only be relaxed later
(Q-26). New public types and members are `@Incubating`: `ConsumerImplemented`, `@TopolithModule.provides`,
`TopolithTestSupport`, `ContextCarrier`, and `ModuleAccessDeniedException` if C is picked for Q-22.

## Alternatives

Each section lists the options it rejects. Deferring everything to 0.2 and 0.3, the roadmap's previous plan, was
rejected by the owner (M8 plan).

## Unresolved questions

Q-21's payload (option C) stays open for a later release, which can add it without breaking 0.1.0. A second
supported circuit-breaker implementation (D-124) and the events loopback follows (D-120) are built when they land.
