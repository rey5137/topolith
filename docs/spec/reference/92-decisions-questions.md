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
| D-3 | The contract interface carries **`@HttpExchange`** by default; a separate HTTP interface stays supported (amended by D-53: the `@RestController` implements the contract) | `*-api` needs only `spring-web` annotations, not MVC. Spring MVC maps `@HttpExchange` on a `@RestController` implementation (Framework 6.1+), so one interface serves client and server and they can't drift. | RFC 0001 §5, §11 |
| D-4 | Boundary and data-ownership checks **`fail` by default**; an adopted legacy repo starts from an ArchUnit **frozen baseline** that can only shrink | New code stays clean; old code starts green and only improves. Noisy, unranked violations kill adoption (Packwerk retrospective). | RFC 0001 §8, §11 |
| D-5 | **Native images are out of 0.1.** Mode resolution stays deterministic from configuration so AOT works later; the AOT guard (INV-9) arrives in 0.2. | Not on the critical path; the design already allows it | RFC 0001 §9, §11 |
| D-6 | **No in-JVM isolation of unmodified services** (child contexts). Legacy services join through the adoption checklist and are consumed `external` until then. | A child context sees every parent bean, so legacy auto-config silently uses the host's infrastructure; servlets can't be added after the host server starts; classpath-root resources shadow each other; one classpath forces one library version on all. | RFC 0001 §12 rows 1–2 |
| D-7 | **Inclusion-based activation:** the host scans only its own package; an import selector imports internal modules (D-30; first written as a registrar) and an `AutoConfigurationImportFilter` drops non-internal modules' auto-configurations; migrations live under `db/migration/<id>` | Component-scan exclusion misses auto-configurations, `@Import`, entity scanning and Flyway's merged migration path, breaking INV-2 | RFC 0001 §12 row 3 |
| D-8 | **Modes are explicit in hosts** (INV-3); only a standalone `*-service` defaults its own module to `internal` | A transitive dependency on an implementation JAR must never activate a module silently | RFC 0001 §12 row 6 |
| D-9 | The descriptor is **generated** from `@TopolithModule` on `package-info.java` by an annotation processor; a hand-written one is verified against the bytecode in the build | Hand-written package lists drift on refactoring; `.properties` has no trailing comments | RFC 0001 §12 row 7; Amended by D-19 |
| D-10 | **Baseline:** Java 21, Spring Boot 4.2, Spring Framework 7; Spring Cloud 2026.0 only for discovery | Current GA lines at project start; HTTP service groups and their LoadBalancer configurer need Framework 7 / Boot 4 | —; Amended by D-20 |
| D-11 | **A mode change needs a restart.** If an `EnvironmentChangeEvent` (Spring Cloud `/actuator/refresh`, a Config Server push) changes any `topolith.modules.<id>.mode`, Topolith logs a `TLnnnn` warning that names each changed module with its running and requested modes, and the actuator endpoint reports a restart as pending. The running topology does not change. | INV-9 fixes the topology at context refresh, because a mode decides which bean definitions, auto-configurations, entities and migrations exist, and `@RefreshScope` can only rebuild beans that already exist. Ignoring the change without a word would break INV-4. Switching to `internal` after a restart still needs the implementation JAR on the host's classpath (P-3). | INV-4, INV-9 |
| D-12 | **An `external` module's endpoint settings can change on refresh.** On an `EnvironmentChangeEvent` that changes `remote.url`, timeouts, or retry or circuit-breaker settings, Topolith rebuilds that module's HTTP service group client. Calls already in flight finish on the old client. Changing between `remote.url` and `remote.service-id` changes the module's wiring, so it is treated like a mode change and gets the D-11 warning instead. | These settings aren't topology, so INV-9 allows them to change. Endpoints already move at runtime through `remote.service-id` and discovery, and requiring a restart to repoint a URL or adjust a timeout would gain nothing. | Q-2 (answered); Amended by D-15, D-16, D-51 |
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
| D-32 | Module persistence; see below | Flyway scans `db/migration` recursively and module beans make Boot's conditions back off | M1.4 architect pre-read; owner |
| D-33 | Internal contract agreement; see below | API and implementation contracts must agree | Owner |
| D-34 | Version mismatch warning; see below | Warn at readiness without changing availability | Owner |
| D-35 | Running topology endpoint; see below | Expose sorted running modes through optional actuator | Owner |
| D-36 | Unregistered repositories fail startup (TL0024); see below | TL0018 turns off Boot's default scan of module packages; JPA and JDBC are compensated | M1 gate architect review; owner |
| D-37 | A standalone host hosts only its own module (TL0025); see below | `topolith.standalone` exempts a module from TL0001, TL0018 and TL0019 and must not let a composed host use that | M1 gate architect review; owner |
| D-38 | `TopolithCode.defaultSeverity()`; see below | The code fixes only a default severity; the effective one is resolved later (P-2, R-VAL-04) | M1 gate architect review; owner |
| D-39 | The processor writes the lowest format-version that expresses the descriptor; see below | A format bump reaches consumers only when a provider uses a new feature | M1 gate architect review; owner |
| D-40 | External client properties map `remote.*` to Boot's service-client keys; see below | One source of truth per setting, no silent winner | M2.1; owner |
| D-41 | An external module without a usable client fails with TL0026; see below | A silent skip hid a missing starter or an unannotated contract | M2.1 review; owner |
| D-42 | A service id maps to `lb://<service-id>` in `topolith-spring-boot`; TL0009 when LoadBalancer is absent | Spring Cloud Commons already resolves `lb://` group base URLs, so no Cloud code is needed | M2.2; owner |
| D-43 | Contract exceptions cross HTTP as Topolith problems; transport failures become `ModuleUnavailableException`; TL0027 for exceptions that cannot cross; see below | INV-6 without silent fallbacks (INV-4); relaxing later stays compatible (D-27) | M2.3 architect pre-read; owner |
| D-44 | Exposure is `topolith.modules.<id>.expose.enabled`, default `false` in every topology; it governs all of an internal module's routes; TL0003 for a module that is not internal, TL0028 on WebFlux; see below | A monolith serves nothing it did not choose to; one switch, one prefix | M2.4 owner decision |
| D-45 | A caller's base URL is `remote.url` + `/<id>` (`lb://<service-id>/<id>`); contract paths are relative to it; see below | The provider's fixed prefix (D-44) is the caller's too, so a contract carries no module prefix | M2.4 owner decision |
| D-46 | Security hook: public `@Incubating` `ExposedEndpoints` and `ExposedRequestMatcher`; Topolith registers no `SecurityFilterChain`; see below | A host secures `/<id>/**` with its own chain (INV-4); Spring Security stays optional (INV-7) | M2.4 owner decision |
| D-47 | The D-2 boundary is an advisor on an internal provider bean, counted by `topolith.contract.calls.in.transaction`; see below | Makes D-2 enforceable for every caller without a proxy per call site | M2.4 owner decision |
| D-48 | A host configures one external group with `ModuleClients.configure`; see below | Explicit customization preserves Topolith settings | M2.5 owner decision |
| D-49 | An external contract may map to a separate HTTP interface; see below | Keep API contracts free of spring-web | M2.5 owner decision |
| D-50 | A refresh swaps delegates inside the group's one `RestClient`; see below | Proxies, error translation and customizers stay as built | M2.6; mechanism is the orchestrator's call, owner may revisit |
| D-51 | Error translation sees through Spring Cloud CircuitBreaker; see below | Keep INV-6 when a breaker wraps the call | M2 gate F1; amends D-12; Q-23 |
| D-52 | `ModuleClients.configure(id, customizer)` replaces `restClientBuilder`; `ModuleUnavailableException` is `@Incubating`; see below | The method returns a group configurer, and every public type is incubating until 1.0 | M2 gate N1, N2; amends D-48 and D-43 |
| D-53 | The contract's server-side implementation is the module's `@RestController`; see below | A service-only implementation uses a D-49 separate HTTP interface | M2 gate F2; amends D-3 and RFC 0001 §5 |
| D-54 | INV-1 allows the types of `io.github.rey5137.topolith.contract` in business code; see below | Contract exceptions are Topolith types a module must throw and catch | M2 gate F4; amends INV-1; Q-20 |
| D-55 | INV-6's "same exception types" covers declared contract exceptions and `ModuleUnavailableException`; see below | Undeclared exceptions are outside the guarantee in 0.1 | M2 gate F5; amends INV-6; Q-22 |

### D-49 — Separate HTTP interface (M2.5)

`topolith.modules.<id>.remote.http-interface.<contract FQN>=<interface FQN>` maps a provided contract to a
loadable interface extending it and carrying `@HttpExchange` on the type or a method. Invalid interfaces fail TL0026
naming the module, contract and interface. A mapping for a non-external module or an unprovided contract fails TL0003.
Mapping keys are case-sensitive class names and must come from a case-preserving property source; environment variables
are unsupported for these mappings. The mapped interface is registered in the module's HTTP group and its proxy supplies the contract. A provider controller
implements the HTTP interface and is exposed under `/<id>` (D-44); the contract needs no spring-web annotation.

### D-48 — Client customization (M2.5)

The host declares `ModuleClients.configure(id, customizer)` as a bean. Unknown or non-external ids fail
TL0003 naming `topolith.modules.<id>.mode`. The customizer cannot replace the base URL or request factory; attempts
to do so fail, preserving the Topolith URL and timeouts. Replacing the URI factory, default URI variables, interceptor list or message converters and cloning the
builder are also rejected, preserving error response translation. Topolith retains its error advice.

### D-51 — Translation through Spring Cloud CircuitBreaker (M2 gate)

With Spring Cloud CircuitBreaker on an external group, translation sees through the breaker's no-fallback wrapper; a rejected call is
`ModuleUnavailableException`. Commons' `CircuitBreakerAdapterDecorator` runs each exchange through
`CircuitBreaker.run`; with no fallback it throws `NoFallbackAvailableException(cause)`, so `ContractCallTranslator`
also maps the cause of a `NoFallbackAvailableException` (found by class name; a host's own wrapper exception is left as thrown) when it would map that cause itself, a `java.util.concurrent.TimeoutException` cause (the time limiter) becomes kind `timeout`, and a `CallNotPermittedException` (found by class
name, no breaker library dependency) becomes `ModuleUnavailableException` of kind `circuit open`. A
`NoFallbackAvailableException` with no mappable cause propagates unchanged, since it also wraps ordinary failures.
This amends D-12: retry and circuit-breaker settings are not rebuilt on refresh in 0.1 (Q-23).

### D-52 — Client configurer name and incubating exception (M2 gate)

`ModuleClients.configure(id, customizer)` replaces `restClientBuilder`; it returns a group configurer (a
`RestClientHttpServiceGroupConfigurer`), not a builder. `ModuleUnavailableException` is `@Incubating` like every public
type until 1.0. Amends D-48 (the method name) and D-43 (the exception's stability).

### D-53 — Server-side implementation of a contract (M2 gate)

The contract's server-side implementation is the module's `@RestController` (it may delegate to a non-contract
service); a service-only implementation is exposed through a D-49 separate HTTP interface. Amends D-3 and RFC 0001 §5.

### D-54 — Topolith contract types in business code (M2 gate)

INV-1 reads "...the optional `@TopolithModule` on `package-info.java` and the types of
`io.github.rey5137.topolith.contract`". Amends INV-1; Q-20's ArchUnit rule already allows the `annotation` and
`contract` packages (D-43).

### D-55 — Scope of exception parity (M2 gate)

INV-6's "same exception types" covers declared contract exceptions and `ModuleUnavailableException` (D-43) in 0.1.
Undeclared exceptions are outside the type guarantee; a later parity mode may wrap them at the internal boundary behind
the P-2 switch. Amends INV-6; Q-22 stays open.

### D-50 — Client refresh mechanism (M2.6)

A refresh never replaces the group's `RestClient`, its proxies or the proxy factory advice (D-43), which the
`HttpServiceProxyRegistry` owns. `ExternalClientSwapConfiguration` adds one `RestClientHttpServiceGroupConfigurer` at
the lowest order, for external groups only (INV-2). After Boot's property, LoadBalancer's, D-43's and D-48's
configurers it installs two Topolith-owned delegates in the group builder: a `UriBuilderFactory` over
`<url>/<id>` (or `http://<service-id>/<id>`, which LoadBalancer resolves per request) and a `ClientHttpRequestFactory`
built with Boot's `ClientHttpRequestFactoryBuilder` and `HttpClientSettings` mapped from the group's
`spring.http.serviceclient.<id>.*` properties. Both read an `AtomicReference`. Because the D-48 guard still rejects a
host's `uriBuilderFactory` and `requestFactory`, the delegates stay Topolith's. LoadBalancer's configurer adds its
interceptor only when the startup base URL is `lb://`; a URL to service-id switch is therefore the TL0010 restart case
already required by R-EXT-08, and a service-id to service-id change works through the same interceptor.

`ExternalClientEndpoints.rebuild(id)` runs on a refresh that changes `remote.url`, `remote.service-id` (same kind),
`remote.connect-timeout` or `remote.read-timeout`. It validates with the startup TL0003 rules
(`TopologyResolver.checkRemote`) plus an `http(s)` URL check, recomputes the module's `topolithExternalClients`
properties (the source is replaced, never mutated), builds the new delegates and only then sets both references. A
validation or build failure restores the old source, keeps both references, logs TL0011 and records the failure for
the actuator until a later refresh succeeds. A request already created finishes on the old request factory, which is
not closed on swap (a small, accepted leak per refresh). After a successful swap the remote version check (D-22, D-24)
runs again for that module on a daemon thread.

Boot-side classes hold no Spring Cloud type. The public `@Incubating` `TopolithRefresh.apply(changedKeys)` takes the
changed property keys, computes the requested modes (`TopologyResolver.requested`), TL0010 and restart pending, and
calls `rebuild`; `topolith-spring-cloud` only maps an `EnvironmentChangeEvent` to it
(`TopolithRefreshListener`, registered by `TopolithCloudAutoConfiguration` under `@ConditionalOnClass`). TL0014 names
that listener class. The `topolith` endpoint adds a top-level `restartPending`; each module adds `requestedMode` when
it differs from `mode`, `restartPending` reasons (`mode`, `wiring`) and, while its last refresh failed,
`refresh: {status: failed, code: TL0011, message, at}`. Wiring is decided by the startup base URL: a refresh that moves it between load-balanced (`lb://`, whether set by
`remote.service-id` or an `lb://` `remote.url`) and a direct http(s) URL is a wiring change (TL0010, restart
pending, no swap). A removed timeout falls back as at startup: the group key, then Boot's global
`spring.http.clients.*`, then 2s/5s. A change to `topolith.standalone` re-runs the mode comparison. A refreshed mode that
does not resolve is still a requested mode change: TL0010 names the running mode and the raw value, with the hint
that startup would fail (TL0003), and restart is pending until the value matches the running mode again. Default URI
variables are protected like the base URL (D-48), because the swapped URI factory would drop them. Boot's own `ssl`, redirect and cookie settings of the group
are mapped once at startup and carried into each rebuild; only the two timeouts change.

A host's own `RestClientHttpServiceGroupConfigurer` that sets `requestFactory` for all groups, without a guard for
Topolith's external groups, may win over the swap (it runs at the same lowest precedence order), so refreshed timeouts
would not apply to that group. A refresh that changes only Boot keys (`spring.http.serviceclient.<id>.*`,
`spring.http.clients.*`) is restart-only: it is not applied and not checked against Topolith's keys until the next
start.

### D-44 to D-47 — Exposure, caller URL, security hook and transaction boundary (M2.4)

- **D-44 Exposure.** `topolith.modules.<id>.expose.enabled` (default `false`, so a monolith serves nothing). It governs
  every handler that `ModuleHandlers.internalOwner` assigns to the module (D-30): exposed, they are mapped under the
  fixed `/<id>` prefix (Spring MVC `PathMatchConfigurer.addPathPrefix`); not exposed, they are removed from every
  `RequestMappingHandlerMapping` and not mapped at all. Host handlers are untouched. `expose.enabled=true` for a module
  that is not `internal`, or a value that is not a boolean, fails startup with TL0003. Exposure on a reactive (WebFlux)
  host fails with the new TL0028: only Spring MVC is served, and D-43's resolver has no reactive counterpart.
  Functional (`RouterFunction`) routes are not module handlers and are not governed.
- **D-45 Caller URL.** The external group's `base-url` is `remote.url` with trailing `/` removed plus `/<id>`, and
  `lb://<service-id>/<id>` for a service id. TL0003's comparison with Boot's `spring.http.serviceclient.<id>.base-url`
  uses that prefixed value. A contract's `@HttpExchange` paths are relative to it, so the contract carries no module
  prefix; a provider maps the same contract under `/<id>` by D-44.
- **D-46 Security hook.** `ExposedEndpoints` (public, `@Incubating`, a bean whenever a module is internal in a servlet
  host) gives `moduleIds()`, `prefixes()` and `patterns()` (`/<id>/**`), with no Spring Security types.
  `ExposedRequestMatcher` (public, `@Incubating`, a bean only when `spring-security-web` is present) matches a request
  under any exposed prefix and nothing when none is exposed; a host passes it to its own
  `http.securityMatcher(...)`. Topolith registers no `SecurityFilterChain` (INV-4), and `spring-security-web` is an
  optional dependency of `topolith-spring-boot` only (INV-7).
- **D-47 Transaction boundary.** A `BeanPostProcessor` adds an advisor, outermost, to each bean of an internal module
  that implements a contract the module provides; it applies `NOT_SUPPORTED` to calls of that contract's methods for
  every caller, the module's own code included, by suspending the current transaction of each
  `PlatformTransactionManager`. The callee's own `@Transactional` then decides, because the advisor sits in front of
  Spring's transaction interceptor. A call made while a transaction is active increments the Micrometer counter
  `topolith.contract.calls.in.transaction` tagged `module` and `contract` (only when Micrometer and a `MeterRegistry`
  exist). No `PlatformTransactionManager` definition: no advice. A `none` or external module has no advice (INV-2).
  Calls that do not go through the contract interface are not a boundary. Managers are suspended in bean-name order and
  resumed in reverse; a failure while suspending or resuming still resumes the rest and the first failure propagates
  with the others suppressed. A `final` handler class (user class annotated `@Controller`, `@RestController` or `@RequestMapping`, merged) cannot take
  the advice, so such a provider bean of an internal module that implements a provided contract fails startup with
  TL0029 whenever a `PlatformTransactionManager` exists, as Spring's own `@Transactional` does; an interface-only proxy
  would hide the class's routes. A `final` non-handler (a lambda, a JDK proxy class) gets the interface proxy, and a
  CGLIB proxy that is already frozen keeps a class-based proxy. The managers are looked up on every call, so one created
  after startup is included.
- **Version endpoint (R-VAL-04).** An exposed module answers `GET /<id>/.topolith/api-version` with its module
  descriptor's `api-version` as `text/plain`. At readiness the host reads it through each external module's own client
  builder (its base URL, mandatory timeouts and load balancing apply): a major that differs from the host API
  descriptor's is TL0023, an unreachable endpoint, a non-2xx answer or a body that is not `<major>[.-]...` is TL0015.
  Both are warnings, logged once per module; neither fails startup or changes readiness (D-24, D-34). The remote
  reads run asynchronously on one daemon thread started by the ready event, so a provider's timeouts never delay
  readiness; the internal TL0023 comparison stays synchronous. The check, TL0028 and the module prefixes are eager
  under lazy initialization. Exposed modules' prefixes take precedence over a host's own `addPathPrefix` (the module
  configurer is ordered first), and the version endpoint has its own handler mapping ahead of annotated controllers,
  so a module's wildcard route cannot shadow it.

Rationale: owner decisions of 2026-11-25. Source: M2.4.

### D-40 — External client properties

External modules map `remote.url`, `remote.connect-timeout`, and `remote.read-timeout` to the corresponding
`spring.http.serviceclient.<id>.base-url`, `connect-timeout`, and `read-timeout` properties. Timeouts default to
2s and 5s, written for a group only when neither its `spring.http.serviceclient.<id>.*` key nor Boot's global
`spring.http.clients.*` key is set. A Boot-only value overrides the timeout default; conflicting explicitly configured Topolith and Boot
values fail with TL0003 naming both keys, while equal values are accepted. Properties remain refreshable-friendly;
client refresh is deferred to M2.6.

### D-41 — An external module needs a usable client (TL0026)

Startup fails with TL0026, naming the module and where relevant the contract, when an external module has (a) no HTTP
client stack (no spring-web, or no Boot HTTP service-client auto-configuration; add `spring-boot-starter-restclient`),
(b) a contract in its `provides` that cannot be loaded, or (c) a provided contract with no `@HttpExchange` on the type
or a method. D-49 relaxes (c) only for mapped contracts. TL0009 stays Cloud-only (D-42).

### D-42 — Service-id discovery (TL0009)

An external module with `remote.service-id` maps it to `spring.http.serviceclient.<id>.base-url=lb://<service-id>`,
which Spring Cloud Commons' `LoadBalancerRestClientHttpServiceGroupConfigurer` resolves through Spring Cloud
LoadBalancer and any `DiscoveryClient`. The mapping lives in `topolith-spring-boot` and references no Spring Cloud type
(INV-7); `topolith-spring-cloud` has no code yet (M2.6 refresh lands there). An explicit Boot `base-url` that differs
from `lb://<service-id>` fails with TL0003 naming both keys (D-40). When a module sets only a service id and Spring
Cloud LoadBalancer is not on the classpath, startup fails with TL0009 naming the module and
`spring-cloud-starter-loadbalancer`. A URL-only module never needs Cloud.

### D-43 — Contract exceptions across HTTP (M2.3)

- **Declared exceptions.** A declared exception is an unchecked exception whose class, or nearest non-abstract
  superclass, lies in an api package the host knows (`Topology.apiPackages()`, exact packages). Only the top-level
  exception counts, never its cause chain. `ModuleUnavailableException` is always declared.
- **Provider.** A `HandlerExceptionResolver`, placed ahead of `ExceptionHandlerExceptionResolver`, acts only on
  handlers whose bean type lies in an internal module's `package` (subpackages included), and only on a declared
  top-level exception. It answers `application/problem+json` with:
  - `type` set to `urn:topolith:problem:contract-exception`;
  - status from `@ResponseStatus` (default 500; 503 for `ModuleUnavailableException`; a non-error status, which
    TL0027 rejects, falls back to 500);
  - `detail` = the message, except for `ModuleUnavailableException`, whose detail is the fixed message below;
  - properties `exception` (the declared class name) and `module` (the provider id, or the id a
    `ModuleUnavailableException` carries).

  On those handlers it takes precedence over host and controller advices. It declines everything else, and never
  sends an undeclared exception's message. Before writing it drops whatever the handler had buffered (body, content
  type), keeping the other headers. It is registered through the topology import path, only in a servlet
  application with `DispatcherServlet` and at least one internal module.
- **Caller.** An advice at index 0 of each external group's service proxy, added through one
  `RestClientHttpServiceGroupConfigurer` on Topolith's groups only. It rebuilds the named class when the body carries
  Topolith's `type`, the name is a valid, length-capped class name in a caller-known api package, and the class loads
  with `initialize=false` through the contract's class loader, is a public concrete `RuntimeException`, and has a
  public `(String)` (preferred) or no-arg constructor. The HTTP exception is added as suppressed, not as the cause.
  No Java serialization and no Jackson polymorphic typing.
- **`ModuleUnavailableException`.** `io.github.rey5137.topolith.contract.ModuleUnavailableException` in
  `topolith-core` (final, unchecked, carries the module id; not a `TopolithException`, D-28). It is raised, with the
  original exception as cause, for an I/O failure, a timeout, no LoadBalancer instance, a 502/503/504 without a
  Topolith problem, or a propagated one (its `module` checked against the D-27 id pattern). After a read timeout the
  call may have run. Because it is declared and travels on, its message is fixed, `module '<id>' could not be reached
  (<kind>)` with kind `connection failure`, `timeout`, `status 502|503|504`, `no instance` or `unavailable
  downstream`; URLs and downstream bodies stay in the cause, and a provider re-sending one sends only that shape.
- **Other failures** raise an unspecified unchecked exception.
- **TL0027** fails startup, for internal and external modules in every topology, when a contract method declares a
  checked exception or a concrete unchecked exception class in the module's api packages is not public, has neither
  a public `(String)` nor a public no-arg constructor, or carries a `@ResponseStatus` (direct, composed or inherited)
  that is not 4xx or 5xx, which a caller would read as a result. Contracts load with `initialize=false`; api package classes are
  read as class files and never loaded, so an external module's classes stay unloaded (INV-2).
- **Convention.** A declared exception's `(String)` constructor takes the message verbatim. Fields beyond the message,
  the cause chain and the provider's stack trace do not cross (Q-21); a subclass is rebuilt as its declared ancestor.

Rationale: INV-6 without silent fallbacks (INV-4); relaxing later stays compatible (D-27). Source: M2.3 architect
pre-read; owner.

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
(TL0008), modes (TL0001, TL0016, TL0002, TL0025), requirements (TL0004), providers (TL0005, TL0022), endpoints
(TL0003, checked by the host after `Topology.resolve`), and cached as the `topolithTopology` bean. TL0004
checks internal modules against `internal` plus `external` modules (D-21).

Rejected: inferring the standalone service from the classpath or the artifact name, binding straight to an enum, and
ignoring unknown ids. Known limit: an environment variable drops the hyphens of a map key, so a hyphenated module id
set only by environment variable fails loudly (TL0001 and TL0016). The unknown-id check (TL0003) treats a key equal to a known id with its hyphens removed as known.

### D-32 — Module persistence (M1.4)

Internal modules use the host's JPA and Spring Data JDBC by default.
- **Entities.** Topolith declares `persistenceManagedTypes` from the host's entity-scan or auto-configuration packages
  plus each internal shared-persistence module's `package`, less api packages. It backs off for a host-declared one.
- **Repositories.** A registrar ordered after Boot's repository auto-configuration registers repositories per internal
  module for JPA and JDBC in 0.1, with fully qualified names. It restores Boot's scan of the host's packages when only module registrations
  made Boot back off.
- **Migrations.** They live at `classpath:db/modules/<id>`. A module uses Flyway (scripts named by Flyway's rules, or
  a Java migration in that package) or Liquibase (one master `db.changelog-master.<yaml|yml|xml|json|sql>`), never
  both. Each internal shared-persistence module gets its own non-default runner: a Flyway run with history
  table `flyway_schema_history_<id>`, or a Liquibase run with tables `databasechangelog_<id>` and
  `databasechangeloglock_<id>` (hyphens become underscores; a table name longer than 63 characters is TL0020). Each
  copies only an allow-list of the host's settings for its tool and not the version-selecting ones: for Flyway the
  connection, schema, encoding, placeholder, validation, retry and transaction settings, never target, cherry pick,
  skip, out of order, ignore patterns, callbacks, Java migrations, providers or file naming; for Liquibase the
  DataSource, schemas, tablespace, contexts, label filter and licence settings, never drop, clear checksums, tag,
  rollback testing, parameters or the customizer. The settings come from the host's default runner bean, and from
  `spring.flyway.*` or `spring.liquibase.*` when the host switched its own off. `spring.flyway.enabled=false` and
  `spring.liquibase.enabled=false` switch off only the host's own runner (owner decision, 2026-11-25): module runners
  still run on the default DataSource; a module whose tool is not on the class path, or that has no DataSource, is
  TL0020. Every module runner, of either tool, waits for every host runner (Flyway's, Liquibase's and Boot's SQL-script
  initializers: `spring.sql.init` schema and data scripts, and the Batch, Quartz and Session schemas) and for the
  runners of the modules it requires, whichever their tool, then runs by id. Boot runs every Liquibase before every
  Flyway initializer, so a module run is neither a `SpringLiquibase` nor a `FlywayMigrationInitializer` but a Topolith
  runner that a `DatabaseInitializerDetector` (after Boot's script initializers, before the beans that need the
  database) places after all of them, which also makes JPA wait for it. A module's own Flyway needs a
  `FlywayMigrationInitializer`, or a `Flyway` with `initMethod = "migrate"`; a bare `Flyway` bean is TL0020. When no
  host runner exists, the module runner uses the host's DataSource: a single default candidate, or exactly one
  `@Primary`; several without a primary are TL0020. The
  schema already holds the host's tables, so each Flyway module's history table is baselined at version 0
  and every module script still runs. Flyway needs `flyway-core`, Liquibase needs `liquibase-core`; a module whose tool's
  classes are missing is TL0020. A host that has `liquibase-core` but no
  changelog of its own must give one or set `spring.liquibase.enabled=false`, or Boot's own check stops startup. The
  host's Flyway locations and Liquibase changelog must not reach a module's files (TL0020): the host's changelog is
  parsed without a database, and a change set under `db/modules/` or from a module artifact, or the host's master file
  found twice or in a module artifact, fails startup. A module that contributes a DataSource runs its own migrations.
- **Pattern A.** A module that contributes an EntityManagerFactory owns its JPA entities and repositories. A module that
  contributes a DataSource owns its JDBC repositories. A module that
  contributes a DataSource owns its migrations (a Flyway, `FlywayMigrationInitializer` or `SpringLiquibase` of its own,
  or TL0020). Its persistence beans, `SpringLiquibase` included, must not be default candidates (TL0019) unless
  the module is the standalone module (the module is then the application).
- **Host packages.** Host auto-configuration and entity-scan packages must not overlap a module package (TL0018),
  whatever the modes, so a host that starts in one topology starts in all of them. The standalone module is exempt
  (owner decision, 2026-11-25): its package may lie under, equal to or above the host's, and Topolith then adds the
  parts the host's scans do not cover (component import deduplicates, managed-type packages are deduplicated and
  repository interfaces that already have a definition are skipped); its migrations still run from
  `db/modules/<id>`. Overlap with any other module still fails.

Rationale: Flyway scans `db/migration` recursively, and Liquibase's `includeAll` does too, so a host with default
settings would run every module JAR's scripts, `none` modules included; a host clean or `dropFirst` must not wipe
module tables, and a host that switches off its own tool must not silently drop module migrations; one shared history table breaks independent versioning and switching a module from
`none` to `internal`; a non-deferred selector lets module beans make Boot's conditions back off. Rejected:
`AutoConfigurationPackages` or `EntityScanPackages` registration, a `ManagedClassNameFilter` alone, one
entity-manager factory per module, module locations added to the host's Flyway, a descriptor key for Pattern A, and
removing leaked repository definitions. This replaces `db/migration/<id>` in R-MODE-04 and RFC 0001 §3.2 and §4.3
step 5. Known limits: Envers repositories in the host are not restored; a module `JavaMigration` bean joins the host's
Flyway history; with lazy initialization the TL0021 check forces entity-manager factories to be created. A host
component scan that covers an internal module registers its beans twice; M3.3 owns that (Q-14).
TL0019 is checked only for an internal module, so a host can start with the module `none` or `external` and fail once
it is `internal`: check a module in internal mode (TL0018 holds in every topology; TL0019 does not).

### D-33 — Internal contract agreement (M1.5)

An internal module whose host API descriptor and module descriptor declare different `provides` sets fails with
TL0022. The diagnostic identifies the module and contracts unique to each side and directs the host to align its API
and implementation JAR versions. This answers Q-12.
Like TL0019 (D-32), it is checked only for an internal module, so a host can start with the module `none` or
`external` and fail once it is `internal`: check a module in internal mode before relying on another topology. It is not
a parity bug for the mode-combination matrix; TL0023 is the external analogue at M2.4.

### D-34 — Version mismatch warning (M1.5)

TL0023 warns once when the application becomes ready if a host API descriptor's major version differs from the
internal module descriptor or remote provider. It neither fails startup nor changes readiness. M2.4 owns remote checks.

### D-35 — Running topology endpoint (M1.5)

The optional, read-only `topolith` actuator endpoint reads the cached topology and returns a `modules` object keyed
by module id, sorted by id, each containing a `mode` field. It is conditional on actuator and endpoint availability.
The fields `restartPending`, `requestedMode`, `reasons` and `refresh` were added later; see D-50. The `refresh.message`
has any `user:password@` removed from URLs it contains.

### D-36 — Unregistered repositories fail startup (M1 gate)

A Spring Data repository interface or MyBatis `@Mapper` interface in an internal module's package, or in the host's
auto-configuration packages of a topology with at least one module, that no repository or mapper registration covers
fails startup with TL0024. Registrations are covered however their scanner recorded the interface: constructor argument 0,
a generic constructor argument, or the `factoryBeanObjectType` attribute, as a `Class` or a name. The JPA and JDBC registrations
Topolith makes per module cover their respective repositories. Spring Data enters strict mode with several stores on the class path and
silently skips an interface it cannot assign, and a module's own `@Enable<Store>Repositories` makes Boot's host scan back off, so
without the check an interface can go missing without a message (INV-4). Detection is by class-file metadata and marker
names (`Repository`, `@RepositoryDefinition`, `@NoRepositoryBean`, `@Mapper`); no store library is a dependency, and a host
without Spring Data or MyBatis is unchanged. Interfaces of `none` and `external` modules, and the api packages outside an
internal module, are not scanned, even where the host's auto-configuration package contains them (the standalone module
with mode `none`). An interface is attributed to the most specific package that holds it, so a module under the host's
package is labelled with the module. Member interfaces are skipped, as Spring Data's `considerNestedRepositories=false`
does, and `@Profile` and `@Conditional` on an interface are judged against the application's environment. A hand-built
`MapperFactoryBean` or repository factory bean covers its interface through the `mapperInterface` or
`repositoryInterface` property. There is no setting to silence the check: an interface that is unregistered on purpose
(excluded through `excludeFilters`, or built by hand) is marked with Spring Data's own `@NoRepositoryBean`, which the
scan already skips.

### D-37 — A standalone host hosts only its own module (M1 gate)

When `topolith.standalone=<id>` is set, every other module with a module descriptor must be `external` or `none`; an
`internal` one fails with TL0025 naming both ids. Rejected: inferring the service from the main class or artifact name
(D-31), requiring the application class inside the module's package, and a TL0019-only guard (leaves the TL0018
exemption open).

### D-38 — Default severity per code (M1 gate)

`TopolithCode.defaultSeverity()` is the severity a code has unless a later layer overrides it (P-2 parity relaxed to
`warn`, R-VAL-04 version mismatch warning). `Diagnostic` carries the effective severity. Renamed from `severity()`
before 0.1, because a rename after release breaks callers.

### D-39 — Lowest sufficient format-version (M1 gate)

The processor writes the lowest `format-version` that can express the descriptor. In 0.1 that is always 1. A format bump
then reaches a consumer only when a provider uses a feature of the new format, so consumers need not upgrade before
providers (D-27).

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
| Q-12 | Answered (D-33) | Should startup fail when a host's API JAR `provides` differs from the module descriptor (M1.5)? |
| Q-13 | Open | Who owns the `META-INF/spring.factories` and `*ManagementContextConfiguration.imports` entries of a non-internal module's JAR (M3.3), which no `AutoConfigurationImportFilter` sees, and the guard for Topolith's auto-configuration being excluded while a module is internal? |
| Q-14 | Open | Should Topolith handle general bean replacement (a module's default-candidate `JdbcTemplate`, `ObjectMapper`) and a host scan that covers an internal module, which registers its beans twice (M3.3)? |
| Q-15 | Answered (D-32) | Should Liquibase be supported for module migrations (it has one master changelog with explicit includes)? |
| Q-16 | Open | Should Topolith run a Pattern A module's migrations on the module's own DataSource? |
| Q-17 | Partly answered (M1.6) | Spring Data JDBC is registered per module in 0.1. Should Topolith register R2DBC, MongoDB and MyBatis per module? They stay at TL0024 in 0.1. |
| Q-18 | Open | A host's `mybatis.mapper-locations` (for example `classpath*:mapper/**/*.xml`) can load a `none` module's mappers (INV-2); should it be a check like TL0020's host-location check? |
| Q-19 | Open | A module with no contracts (listener-only, jobs) still has to declare an api package (`@TopolithModule.api()` is required and `api-packages` is non-empty); should that be relaxed (compatible later, D-27)? |
| Q-20 | Open (M3.3) | `Topology` is an injectable public bean, so business code could branch on a module's mode (INV-1, INV-6); should M3.3 add an ArchUnit rule forbidding module code from depending on `topolith-core` (only the `annotation` and `contract` packages allowed, D-43)? |
| Q-21 | Open | Should a contract support checked exceptions (TL0027 rejects them) or carry exception payload beyond the message (fields, cause chain) across HTTP (D-43)? |
| Q-22 | Open | Should a 404, 405 or 415, or a 401 or 403, without a Topolith problem become `ModuleUnavailableException` rather than an unspecified exception, and should a fixed JDK list (`IllegalArgumentException`, `IllegalStateException`, `UnsupportedOperationException`) count as declared (D-43)? |
| Q-23 | Open | Should a refresh rebuild a group's Spring Cloud CircuitBreaker and retry settings (D-12, D-51), or stay restart-only? |
