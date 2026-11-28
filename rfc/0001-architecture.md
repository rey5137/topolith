# RFC 0001 — Topolith architecture

- **Status:** accepted
- **Note:** D-30 (M1.3) replaces the `TopolithImportRegistrar` of §2 and §3.2 with an import selector.
- **Note:** D-32 (M1.4) replaces `db/migration/<id>` with `db/modules/<id>` in §3.2 and §4.3 step 5.
- **Affects:** all initial `INV-*`, `P-*` and `D-1..D-5` (`docs/spec/SPEC.md`, `docs/spec/reference/92-decisions-questions.md`)
- **Discussion:** none (founding RFC)

**Tagline:** *Write modules once. Choose your topology in config.*

## Summary

Topolith lets a Spring Boot business module run **in-process**, **remote** or **not at all**, chosen per host in
configuration, without changing the module's code. Modules live in one multi-module repo or in their own
one-service-per-repo legacy repos, and any set of them can be bundled into one image or split back out. Topolith
enforces module boundaries itself; Spring Modulith is an optional add-on.

## Motivation

Teams choose between a modular monolith and microservices once and then pay for the choice: a monolith that can't
split a module out when it needs to scale, or microservices whose network calls and duplicated infrastructure cost more
than they give. Hand-rolled "local bean or remote client" switches are common, but each one lacks validation, boundary
checks, discovery and event handling. Google's Service Weaver showed demand for choosing the topology in config, and
was shelved because adopting it meant rewriting applications. Topolith takes the same idea with no rewrite: a module
is declared by metadata, and only standard Spring extension points are used.

Requirements:

| # | Requirement |
|---|---|
| R1 | The primary layout is one repo with many Maven modules |
| R2 | Legacy microservices, one per repo, adopt Topolith and migrate over time |
| R3 | Any set of modules from any repos can be bundled into a single image |
| R4 | `external` mode supports a fixed endpoint or service discovery |
| R5 | Topolith enforces module boundaries itself; Spring Modulith is optional |
| R6 | Non-opinionated: as little framework API in business code as possible |

## Design

(Sections are numbered from 2 to keep the cross-references of the original proposal.)

## 2. Principles

1. **A module is metadata.** Business code has no framework base classes. The one annotation is optional and lives in `package-info.java`.
2. **Only standard Spring extension points:** `ImportBeanDefinitionRegistrar`, `AutoConfigurationImportFilter`, `PersistenceManagedTypes`, HTTP service groups, Spring Cloud LoadBalancer and Micrometer Observation. There is no custom container, protocol or broker.
3. **Explicit beats implicit.** A host must state every module's mode. Anything unsatisfiable fails at startup with a fix-it message, and nothing falls back silently.
4. **Defaults can be overridden, but parity can't be bypassed silently.** You can replace the scanning, the client or the prefix. You can only relax the parity rules (§6) to `warn` deliberately.
5. **Strict for new code, a ratchet for old code.** Boundary checks `fail` by default; an adopted legacy repo starts from a frozen baseline that can only shrink (D-4). Packwerk's lesson: noisy, unranked violations kill adoption.

---

## 3. Module model

### 3.1 Descriptor (generated)

```java
// user-module/src/main/java/com/rey/user/package-info.java
@TopolithModule(id = "user", api = "com.rey.user.api", requires = {}, optional = {"notification"})
package com.rey.user;
```

The annotation processor writes `META-INF/topolith/modules/user.properties`. That file is the runtime format.
- `provides` is **inferred** from the interfaces in the api package that the module implements.
- `api-version` comes from `${project.version}`.
- Legacy repos that can't use annotation processing can write the file by hand. The Maven plugin goal `topolith:verify` checks it against the bytecode.

The `*-api` JAR carries `META-INF/topolith/apis/user.properties`, with `id`, `api-packages`, `provides` and `api-version`.

### 3.2 Modes

```yaml
topolith:
  modules:
    user:    { mode: external, remote: { url: http://user-service:8080 } }
    payment: { mode: internal }
    audit:   { mode: none }
```

| Mode | Behaviour |
|---|---|
| `internal` | Imports the module's configuration and packages, its filtered auto-configs, its entities and its migrations (`db/modules/<id>`) |
| `external` | Imports nothing from the module and registers an HTTP service group per module for its `provides` contracts |
| `loopback` | **New, for tests and dev.** The module runs in-process, but every call goes through real HTTP serialization and error mapping on the same server. Optional latency and fault injection. |
| `none` | Imports nothing. Startup fails if a module that is active `requires` it. |

**Activation is inclusion-based.** The host's `@SpringBootApplication` scans only the host package. `TopolithImportRegistrar` (wired by auto-config) registers internal modules. `TopolithAutoConfigurationFilter` removes the `AutoConfiguration.imports` entries owned by non-internal modules. A filtered `PersistenceManagedTypes` bean plus per-module repository registration give each module only its own JPA entities. Pattern A (module-owned `DataSource`/EMF, like `PaymentDatasourceConfiguration` today) still works without changes.

### 3.3 Startup validation

These conditions fail startup with a fix-it message:
- A mode is missing in a host.
- `internal` without an implementation JAR.
- `external` without `url` or `service-id`, or with both.
- An unmet `requires`.
- Two providers of one contract, or a duplicate id.
- A bean or auto-config owned by a non-internal module (ownership = the declaring class or resource).
- A cycle in `requires`.
- An event that would be lost (§7).
- AOT topology drift (§9).

When the api-version majors differ: the check runs at readiness and **warns** by default.

---

## 4. Repositories, legacy adoption and bundling

### 4.1 Framework repo (`topolith`, new)

```text
topolith/
├── topolith-core                 # descriptor model, graph, validation (no Spring Boot)
├── topolith-spring-boot          # activation, modes, HTTP groups, validation, actuator
├── topolith-processor            # @TopolithModule → descriptor
├── topolith-maven-plugin         # verify, topology generate, extraction report
├── topolith-test                 # ArchUnit rules, mode matrix, loopback, by-value proxy
├── topolith-events               # OPTIONAL mode-aware events (Modulith / Spring Cloud Stream SPI)
├── topolith-modulith             # OPTIONAL ApplicationModuleSourceFactory bridge
├── topolith-bom / topolith-platform   # platform BOM: Boot + Topolith + shared libs
└── samples/                      # a reference adopter: user and payment modules, monolith and hybrid hosts
```

### 4.2 Product repo (primary: mono-repo, multi-module)

```text
product/
├── modules/user/{user-api, user-module, user-service}
├── modules/payment/{payment-api, payment-module, payment-service}
└── apps/{monolith, payment-host}
```

Rule: other modules depend only on `*-api`.

### 4.3 Legacy service per repo: adoption checklist

This replaces in-JVM isolation of unmodified services (§12). It is a small, mechanical refactor that `topolith-maven-plugin`'s `adopt` goal reports on and partly automates.

| # | Step | Why |
|---|---|---|
| 1 | Split into a library JAR and a thin `main` | Hosts depend on the library |
| 2 | Add `@TopolithModule` in `package-info.java` | Descriptor |
| 3 | Move the root `application.yml` defaults into `@ConfigurationProperties(prefix="<id>")`, or into `config/<id>.yml` imported by the module | Avoids resources at the classpath root shadowing each other in a bundle |
| 4 | Module-owned `DataSource`/EMF/TX manager (pattern A), or shared JPA with entities filtered | No silent sharing of infrastructure |
| 5 | Flyway location `db/modules/<id>` | Each module's migrations stay separate |
| 6 | Fixed path prefix `/<id>` | URLs stay the same in every topology |
| 7 | Upgrade to the platform BOM's Boot major version | Same JVM, same libraries |

Before step 7, or whenever a service is not ready, others consume it as **external** from day one. It only has to publish `<id>-api`, or consumers write a hand-written client.

If you truly need **one image** before refactoring, run several processes in one container. That is an ops choice outside the framework, and more honest than a leaky in-JVM isolation.

### 4.4 Bundling

A host is a normal Boot app that imports `topolith-platform` and depends on module JARs from any repo. It runs the enforcer rules `dependencyConvergence` and `requireUpperBoundDeps`. CI runs a "latest modules" job that rebuilds every host whenever any module publishes.

---

## 5. External mode

- **Contract:**
  - `user-api`'s `UserOperations` is annotated with `@HttpExchange`.
  - In the module, the `@RestController` implements it (delegating to a service if it likes); a service-only implementation needs a separate HTTP interface (D-53).
  - In external mode, the framework registers an HTTP service group named `user`.
  - Escape hatches:
    - keep a separate HTTP interface, so the contract has no dependency on `spring-web`;
    - or provide a custom client configuration (`ModuleClients.configure("user", customizer)`).
- **Endpoint:**
  - `remote.url` maps to `spring.http.serviceclient.user.base-url`.
  - Alternatively, `remote.service-id` uses Spring Cloud LoadBalancer with `lb://` and any `DiscoveryClient` (Consul, Eureka, Kubernetes, simple). Spring Cloud is needed only for this.
- **Timeouts** are mandatory: there is a framework default of connect 2s / read 5s, and each group can override it.
- **Resilience:** circuit breaker and retry per group come from Spring Cloud. Calls are retried only if they are idempotent.
- **Errors:**
  - Exceptions declared in `*-api` are sent as ProblemDetail and rebuilt as the same type on the client side.
  - Transport failures raise `ModuleUnavailableException`.
- **Exposure:** a fixed `/<id>` prefix per module, and `expose.enabled` (off in a monolith). Exposed endpoints get a `SecurityFilterChain` hook. One discovery registration per app.

---

## 6. Semantic parity

Internal and external calls must behave the same, or switching modes is a trap.

| Concern | Rule | Enforced by |
|---|---|---|
| Transactions | A contract call is never part of the caller's transaction. Internal calls run with `REQUIRES_NEW` or `NOT_SUPPORTED` at the boundary (configurable). | Boundary proxy + ArchUnit (no `@Transactional` spanning modules) |
| Mutation | Arguments and results are values | `by-value` test proxy (JSON round-trip) in `topolith-test` |
| Types | No `Page`, `Stream`, entities, `InputStream` or lazy proxies in contracts | ArchUnit rule from `api-packages` |
| Validation and advice | Calls go to the service, and validation runs at the contract boundary in both modes | Contract implemented by a service, not a controller |
| Auth | Outgoing-auth hook per group (token relay or client credentials). The local path carries the same `Authentication`. | SPI + default token relay |
| Tracing and context | One `topolith.module.call` Observation in both modes, with the same span name and tags `module.id`, `module.mode` and `caller.module`. Context propagation for MDC and tenant. | Micrometer Observation |
| Latency and failure | `loopback` mode + fault injection in the mode matrix | `topolith-test` |

---

## 7. Mode-aware events

- Modules publish events declared in their `*-api`.
- If the publisher and listener are both internal, delivery is the normal Spring application event in-process, with Modulith's Event Publication Registry if it is present.
- If either one is external, delivery goes through an **externalization SPI**. Adapters:
  - Spring Modulith event externalization (Kafka, AMQP, JMS, SQS, SNS), using the transactional outbox;
  - Spring Cloud Stream;
  - a custom adapter.
- If no adapter is available and an event would cross a process boundary, startup fails with the publisher, listener and missing adapter.
- Topolith ships no broker.

---

## 8. Boundary enforcement (Modulith optional)

| Layer | Mechanism | Default |
|---|---|---|
| Build | Modules depend only on `*-api` artifacts | Template default |
| Test | `TopolithArchitecture.verify()`: ArchUnit rules from descriptors (non-api access, undeclared `requires`, cycles, parity types) | `fail`; an adopted legacy repo starts from a frozen baseline (D-4) |
| Startup | No beans or auto-configs of non-internal modules | `fail` |
| Data | Entities and tables owned per module. In tests, a Hibernate `StatementInspector` flags queries that touch another module's tables. | `fail` with a frozen baseline (D-4) |
| Optional | `topolith-modulith` bridge (`ApplicationModuleSourceFactory`): Modulith verification, docs, `@ApplicationModuleTest`, Event Publication Registry | off |

---

## 9. Topology as config, AOT and native

```yaml
# topology.yml (product repo root)
deployables:
  monolith:     { internal: [user, payment] }
  payment-host: { internal: [payment], external: { user: { service-id: user-service } } }
  user-service: { internal: [user], expose: [user] }
```

- `mvn topolith:generate` writes each host's `application-topology.yml`. It can also write Helm values or k8s Services.
- Hosts still work without it.
- For native images, the topology is fixed at build time. The AOT processor records the modes it used, and startup fails if the runtime properties differ.

---

## 10. Roadmap (ranked by impact)

Research basis:
- Google put Service Weaver into maintenance in Dec 2024 because adopting it "required rewriting large parts of existing applications". Users liked its config-driven colocation and its Local/RPC test runners.
- Spring Modulith covers in-process boundaries, events, docs and observability, but has no notion of a *remote* module.
- An empirical study of 2,641 issues names data consistency as the top migration pain.

| Release | Scope | Impact | Exit criterion |
|---|---|---|---|
| **0.1 (MVP)** | core + spring-boot + processor + test + BOM. Modes internal/external/none, inclusion-based activation, fixed `url` and `service-id`, mandatory timeouts, startup validation, ProblemDetail errors, ArchUnit rules. A reference adopter in `samples/` with user and payment modules. | Foundation | The monolith, hybrid and `none` topologies pass the same Payment tests. `none` leaves no beans, routes, tables or auto-configs. |
| **0.2: Parity and loopback** | §6 rules, `loopback` mode, by-value proxy, fault injection, mode matrix, Observation tags | **H.** No other Spring tool proves that a module behaves the same before it is extracted. | A deliberately mutable DTO or `Page` return fails the tests |
| **0.3: Mode-aware events** | §7 SPI + Modulith externalization adapter | **H.** Covers the top migration pain. | An event crosses user→payment the same way in monolith and hybrid |
| **0.4: Topology as config + extraction report** | `topology.yml`, `topolith:generate`, and `topolith:report <id>`: which calls become remote, chatty call chains, transactions spanning the module, shared tables, non-serializable contract types | **H.** The headline demo: turns "should we split payment?" into evidence. | The report on `payment` lists every blocker found by hand |
| **0.5: Legacy adoption** | `topolith:adopt` goal (checklist §4.3), per-module config files, data-ownership checks, pilot on one real legacy repo | **H** for R2 | One legacy repo is bundled into the monolith image and still runs standalone |
| **Later: Ecosystem** | Modulith bridge, actuator `/topolith`, Backstage `catalog-info.yaml` export, graph docs coloured by mode, japicmp-based api-compatibility gate, hybrid local dev (`dev-remote` profile), Consul registrar SPI | M | — |
| **Not planned** | Runtime topology change without a redeploy; in-JVM isolation of unmodified services; a built-in saga engine (only adapters for Temporal, DBOS or Dapr if asked) | — | — |

**The five features that set Topolith apart from Spring Modulith alone:** mode-aware events, topology as config, the extraction readiness report, loopback with the parity test matrix, and data ownership checks.

---

## 11. Decisions (D-1..D-5)

| # | Decision | Decision | Why |
|---|---|---|---|
| 1 | Name and groupId | **Topolith**, with groupId `dev.topolith` if the domain is free, otherwise `io.github.rey5137`. Decide before the first Maven Central release. | A groupId can't be changed cheaply after release |
| 2 | Transaction at the internal boundary | **`NOT_SUPPORTED`**: suspend the caller's transaction, and the callee's own `@Transactional` decides | It matches remote behaviour exactly. `REQUIRES_NEW` forces a transaction the remote callee might not open, and holds two connections at once (pool starvation). Also emit a warn metric when a contract is called inside an active transaction. |
| 3 | `@HttpExchange` on the contract | **Yes, as the template default.** A separate HTTP interface stays supported. | It only needs `spring-web` annotations, not MVC. A single interface keeps client and server from drifting apart. |
| 4 | `warn` or `fail` | **`fail` by default**, plus an ArchUnit **freeze baseline** for adopted legacy repos | New code stays clean. Legacy repos start green and can only improve, a ratchet instead of noise (Packwerk's lesson). |
| 5 | Native images in the MVP | **No.** Keep mode resolution deterministic so AOT works later; the AOT guard arrives in 0.2. | Not on the critical path, and the design already allows it |

---

## 12. Alternatives considered

An earlier draft of this RFC took the positions in the second column. An independent architect review rejected them for the reasons given.

| # | Earlier position | Problem found | Decision here |
|---|---|---|---|
| 1 | `isolated` child contexts bundle **unmodified** legacy services | **Blocker.** A child context sees every parent bean. Because `@ConditionalOnMissingBean` searches the parent too, the legacy auto-config backs off and silently uses the host's `DataSource`, `ObjectMapper` and `SecurityFilterChain`. | **`isolated` is cut.** Legacy services join through a short **adoption checklist** (§4) that turns them into `shared` modules. Until then they run **external**. |
| 2 | Same | **Blocker.** Servlets can't be added after the host server has started. Every legacy JAR ships its own `application.yml`, `logback.xml` and `db/migration` at the classpath root, so one copy shadows the others. Maven resolves one version of each library for all of them. The child's security filter chain is silently dropped. | Same as above |
| 3 | `TypeExcludeFilter` *exclusion* | It only affects component scanning. Module auto-configs, `@Import`, entity scanning and Flyway migrations still leak through, so a module set to `none` still creates its tables. | **Inclusion-based activation:** the host scans only its own package, and Topolith imports internal modules explicitly. An `AutoConfigurationImportFilter` drops auto-configs of non-internal modules. Migrations live under a per-module location. |
| 4 | Only errors made consistent across modes | Transactions, pass-by-reference, validation, auth, tracing, paging/streaming and latency all differ between local and remote calls | A new **Semantic parity** section (§6) with enforced rules and a **loopback** mode |
| 5 | Events not covered | When a listener's module turns external, in-JVM events are silently dropped | **Mode-aware events** (§7). Startup fails if an event would be lost. |
| 6 | Mode defaults to internal when the impl is on the classpath | A transitive dependency on `user-module` silently activates the module | Hosts must set each mode **explicitly**. Only a standalone `*-service` defaults to internal. |
| 7 | Hand-written `.properties` descriptor | `.properties` doesn't allow trailing `#` comments, so the earlier examples were wrong. Hand-written files drift when packages are refactored. | The descriptor is **generated** from `package-info.java` by an annotation processor and verified in the build (§3) |
| 8 | Only a note on the Boot major-version constraint | Library versions don't converge when independently released JARs are bundled | **Platform BOM** plus enforcer `dependencyConvergence` and `requireUpperBoundDeps`. CI rebuilds every host against the latest modules. |
| 9 | AOT/native not mentioned | AOT freezes scanning and conditions, so changing a mode at runtime is silently ignored | **The topology is fixed at build time for native images.** An AOT guard fails startup on a mismatch. |
| 10 | Startup bean-graph dependency check | `ObjectProvider` and `@Lazy` injection are missed, and checking by bean type gives false positives | The startup check only verifies that no beans are owned by non-internal modules (ownership = the declaring source). ArchUnit handles dependency rules. |
| 11 | `path-prefix` set per deployment | Client URLs would differ between topologies | **The prefix is fixed per module** (`/user`). Deployments don't change it. |
| 12 | Multi-id Consul registrar in P1 | This is custom Consul work, and it is not needed | **Deferred.** One registration per app; each module is reached by path. |
| 13 | P0 too large | Boot upgrade, restructure, framework and bug fixes behind one exit criterion | A smaller 0.1 scope (§10) |

Confirmed: Spring MVC maps `@HttpExchange` interfaces implemented by a `@RestController`. This has worked since Framework 6.1 and is documented in 7, with one endpoint per method and a subset of parameter types. One contract interface therefore serves both the server and the client.

## Compatibility

Nothing is released yet. Every public type ships `@Incubating` until 1.0.

## Unresolved questions

None blocking 0.1. New questions are recorded as `Q-n` in `docs/spec/reference/92-decisions-questions.md`.

## Sources

- Spring MVC `@HttpExchange` server-side support: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html>
- HTTP service client groups: <https://spring.io/blog/2025/09/23/http-service-client-enhancements/>, <https://docs.spring.io/spring-boot/reference/io/rest-client.html>
- Spring Cloud LoadBalancer: <https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/loadbalancer.html>
- Spring Modulith: events <https://docs.spring.io/spring-modulith/reference/2.2/events.html>, observability <https://docs.spring.io/spring-modulith/reference/production-ready.html>, docs <https://docs.spring.io/spring-modulith/reference/documentation.html>, multi-JAR discussion <https://github.com/spring-projects/spring-modulith/discussions/151>
- Service Weaver: maintenance notice <https://awesome.ecosyste.ms/projects/github.com%2FServiceWeaver%2Fweaver>, docs <https://serviceweaver.dev/docs.html>, paper <https://arxiv.org/html/2404.09357v1>
- Migration pain points: <https://arxiv.org/html/2302.01894v2>, <https://www.arxiv.org/pdf/2103.00170>
- Packwerk retrospective: <https://railsatscale.com/2024-01-26-a-packwerk-retrospective/>
- Prime Video consolidation: <https://bytebytego.com/guides/amazon-prime-video-monitoring-service/>
- Quarkus Backstage: <https://docs.quarkiverse.io/quarkus-backstage/dev/catalog-info.html>
- Name checks: <https://markinton.com/trademark/cleave-79348185>, <https://central.sonatype.com/artifact/io.github.simplezac1995/strata-spring-boot-starter>, <https://github.com/ConsenSys/tessera>, <https://github.com/holon-platform/platform>
