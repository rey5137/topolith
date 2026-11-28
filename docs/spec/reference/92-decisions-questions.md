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
| D-12 | **An `external` module's endpoint settings can change on refresh.** On an `EnvironmentChangeEvent` that changes `remote.url`, timeouts, or retry or circuit-breaker settings, Topolith rebuilds that module's HTTP service group client. Calls already in flight finish on the old client. Changing between `remote.url` and `remote.service-id` changes the module's wiring, so it is treated like a mode change and gets the D-11 warning instead. | These settings aren't topology, so INV-9 allows them to change. Endpoints already move at runtime through `remote.service-id` and discovery, and requiring a restart to repoint a URL or adjust a timeout would gain nothing. | Q-2 (answered); Amended by D-15, D-16, D-51, D-104 |
| D-13 | Computed restart status; see below | Avoid stale mode status | Q-3 (answered) |
| D-14 | Keep the old client on failed refresh; see below | Preserve service during a failed swap | Q-4 (answered) |
| D-15 | Rebuild on service-id changes; see below | Endpoint changes need no restart | Q-5 (answered) |
| D-16 | Defer resilience policy; see below | Keep 0.1 to mandatory timeouts | Q-6, Q-8 (answered); Amended by D-74; Superseded by D-100..D-105 |
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
| D-51 | Error translation sees through Spring Cloud CircuitBreaker; see below | Keep INV-6 when a breaker wraps the call | M2 gate F1; amends D-12; Amended by D-102, D-104, D-105; Q-23 (answered) |
| D-52 | `ModuleClients.configure(id, customizer)` replaces `restClientBuilder`; `ModuleUnavailableException` is `@Incubating`; see below | The method returns a group configurer, and every public type is incubating until 1.0 | M2 gate N1, N2; amends D-48 and D-43 |
| D-53 | The contract's server-side implementation is the module's `@RestController`; see below | A service-only implementation uses a D-49 separate HTTP interface | M2 gate F2; amends D-3 and RFC 0001 §5 |
| D-54 | INV-1 allows the types of `io.github.rey5137.topolith.contract` in business code; see below | Contract exceptions are Topolith types a module must throw and catch | M2 gate F4; amends INV-1; Q-20 |
| D-55 | INV-6's "same exception types" covers declared contract exceptions and `ModuleUnavailableException`; see below | Undeclared exceptions are outside the guarantee in 0.1 | M2 gate F5; amends INV-6; Q-22 |
| D-56 | `TopolithArchitecture` derives its ArchUnit rules from descriptors; see below | One descriptor-driven source of boundary rules | M3.1 owner decision |
| D-57 | `verifyFrozen` and `TopolithBaseline.generate` freeze and write the baseline; see below | Adopted repos need a frozen, shrinking baseline | M3.2 owner decision |
| D-58 | A malformed packaged baseline fails with TL0030; see below | Invalid freeze data must not silently weaken startup checks | M3.3 owner decision |
| D-59 | Module code may depend only on Topolith annotation and contract packages; see below | Prevent topology-dependent business behavior | M3.3 owner decision; closes Q-20 |
| D-60 | Host scans covering internal modules fail TL0018; module beans making auto-configuration back off fail TL0031; see below | Avoid duplicate registration and silent default replacement | M3.3 owner decision; closes Q-14 |
| D-61 | Non-internal module entries in management imports fail TL0007; see below | Close activation paths outside the imports filter | M3.3 owner decision; answers Q-13 in part |
| D-62 | Test modes use annotations or explicit properties with a per-module mock server; see below | Exercise both activation and external calls | M3.4 owner decision |
| D-63 | A baseline and the startup scan belong to the class-path root holding the module descriptor; see below | One owner per baseline; test roots and other JARs neither add violations nor waive them | M3 gate owner decision; amends D-25, D-57, D-58 |
| D-64 | Stable architecture rule names; see below | The freeze store is keyed by the description; position and prose are not a stable key | M3 gate owner decision; amends D-56, D-57 |
| D-65 | The mock server serves but does not record the readiness version check; see below | Deterministic `requests()` without a public readiness hook | M3 gate; owner |
| D-66 | The sample `none` host is a payment host: it bundles both module JARs with `payment` internal and `user` = `none` (RFC §4.2's `payment-host`); the shared payment tests pass there and AC-TEST-06 checks that `user` leaves no beans, routes, tables or auto-configurations | One `none` topology that is also a deployable shape, and the trace check runs against real module JARs | M4.1; owner |
| D-67 | In M4.1 the hybrid host (`user` internal, `payment` external) tests against `payment-service` started in the same JVM as a second Boot context on a random port, reached over real HTTP at the fixed `url`; M4.2 replaces this with Testcontainers and Consul and drops the test-scope `payment-service` dependency (its JAR puts the payment module on the host's test classpath; `HybridPaymentTest` asserts it leaves no trace) | Real HTTP and no mock before the container harness exists | M4.1; amended by D-69 |
| D-68 | `topolith-bom` is the platform: besides the Topolith modules it imports `spring-boot-dependencies` and `spring-cloud-dependencies` at the baseline versions (D-10, D-20), so an adopter importing only it gets Boot, Cloud and Topolith versions. The sample hosts do not inherit `topolith-parent`: their host parent (`samples/hosts/pom.xml`) has no parent, imports only the BOM and enforces `dependencyConvergence` and `requireUpperBoundDeps`; its literal `revision` is checked against the reactor's by `ac_build_09_*` | Hosts then build exactly like an outside adopter (RFC §4.1/§4.4), and AC-BUILD-09 is load-bearing: without the BOM import no host dependency resolves | M4.1 fix round 1; owner |
| D-69 | The M4.2 hybrid end-to-end tests run `payment-service` as its Boot exec JAR in an `eclipse-temurin:21-jre` Testcontainers container; `maven-dependency-plugin:copy` puts that JAR in the host's `target/`, so neither `payment-service` nor the payment module is on the test classpath. The fixed-URL test maps the container's 8081 to a free host port. The Consul test runs `hashicorp/consul`, registers `payment-service` with `hostname=localhost`, the mapped host port, no health check; the host turns off catalog-watch and its own auto-registration, and reaches it through `remote.service-id` and `lb://` (D-42); the host keeps the fixed `url` default (D-8, INV-4). The tests need `package` or later (the JAR copy runs in `process-test-resources`, after `payment-service` is packaged) and Docker; missing either fails them instead of skipping | Real transport in both modes with no in-JVM leakage (INV-2), and a skipped end-to-end test would hide a broken harness | M4.2; amends D-67 |
| D-70 | A `latest-modules` CI workflow triggers on `workflow_dispatch`, a published release and a push to `main`; on JDK 21 it installs the reactor without `samples/hosts` and its children, then rebuilds the hosts alone with `-U -f samples/hosts/pom.xml verify`, as an outside adopter would. `.github/scripts/test_latest_modules.py` asserts the triggers, the JDK, the host exclusions and their order | Hosts must keep building when modules release independently (R-BUILD-04, AC-BUILD-10) | M4.2; owner |
| D-71 | User documentation lives in `docs/guide/` (`getting-started.md`, `legacy-adoption.md`, `configuration.md`), linked from `README.md`. Every qualifying fenced block in `docs/guide/*.md` is preceded by `<!-- snippet: <repo-relative path>#<tag> -->` pointing at a `snippet:start <tag>` / `snippet:end <tag>` region in a file in a sample Maven module (`samples/**/src/test/`, `samples/**/src/main/`, or `samples/**/pom.xml`, all built by `./mvnw verify`). `.github/scripts/test_docs.py` verifies the three pages, RFC §4.3's seven adoption steps parsed from `rfc/0001-architecture.md`, configuration keys, snippet equality, and R-CAT-03 mode and restart-pending meanings parsed from `docs/spec/reference/90-catalog.md` | Keeps user documentation and examples mechanically in sync with sample modules and the catalog vocabulary (R-BUILD-05, AC-BUILD-11, AC-BUILD-12, AC-CAT-01) | M4.3; owner |
| D-72 | Maven Central publishing uses the **Central Portal** via `org.sonatype.central:central-publishing-maven-plugin` (`0.12.0`, `publishingServerId` `central`, `autoPublish` `false`) in a root `release` profile alongside `maven-source-plugin` (`jar-no-fork`), `maven-javadoc-plugin` (`jar`, `doclint` `all,-missing`) and `maven-gpg-plugin` (`sign`, `bc` signer reading `MAVEN_GPG_KEY` / `MAVEN_GPG_PASSPHRASE`), with `flattenMode` `ossrh` so flattened POMs keep Central-required metadata. The published artifacts are `topolith-parent`, `topolith-bom`, `topolith-core`, `topolith-spring-boot`, `topolith-spring-cloud`, `topolith-processor` and `topolith-spring-test`; all `samples/` artifacts are excluded via `excludeArtifacts`. `.github/workflows/release.yml` runs on `v*` tags (validating `${GITHUB_REF_NAME#v}` against `^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$`, verifying the tagged commit is an ancestor of `origin/main` via `git fetch origin main` and `git merge-base --is-ancestor "$GITHUB_SHA" origin/main`, and verifying `CHANGELOG.md` has `## [<version>]`) and `workflow_dispatch` (`dry_run`, default `true`, adding `-DskipUpload=true`; a non-dry run requires a `refs/tags/v*` ref); its job runs in the `maven-central` environment and reads that environment's secrets `CENTRAL_USERNAME`, `CENTRAL_TOKEN`, `GPG_PRIVATE_KEY` (ASCII-armored) and `GPG_PASSPHRASE`, the names the owner's other release workflows use). CI's `release-dry-run` job signs a `0.1.0` dry-run bundle in `target/central-staging` with a throwaway key and verifies coordinates, `.asc` signatures, `.md5` and `.sha1` checksums, Apache-2.0 license and sample exclusion with `.github/scripts/check_release_bundle.py`. Every public top-level type across all published modules (including `topolith-processor`, which depends on `topolith-core`) carries `@Incubating`, enforced by `IncubatingTest` | Automates Central Portal staging and verification without slowing default builds, keeps `0.1.0` publication manual in the Portal UI, and enforces the pre-1.0 `@Incubating` contract across all published modules (R-BUILD-06, AC-BUILD-13, AC-BUILD-14, AC-BUILD-15) | M4.4; owner |
| D-73 | The user documentation is a MkDocs Material site in `docs/site/` (`mkdocs.yml`, pinned `requirements.txt`, `docs/` as `docs_dir`, `site_dir` `../../target/docs-site`, `strict: true`, `site_url` `https://rey5137.github.io/topolith/`). The three guides moved from `docs/guide/` to `docs/site/docs/guide/`, which supersedes D-71's location only: D-71's snippet rule now covers every page under `docs/site/docs/` that carries a `<!-- snippet: ... -->` marker (mermaid fences need none). The home page shows `docs/site/docs/assets/banner.png` under its H1, and so does `README.md`. Pages are written for users and carry no `R-*`, `AC-*`, `INV-*` or `D-*` ids; the diagnostics page lists every `TLnnnn` code of `reference/90-catalog.md`. CI's `docs-site` job runs `mkdocs build --strict` on every PR, and `.github/workflows/docs.yml` deploys `target/docs-site` to GitHub Pages on a push to `main` that touches `docs/site/**` (or by `workflow_dispatch`). `.github/scripts/test_docs.py` checks the strict setting, nav completeness, the code set, the workflows and the banner | One copy of each user page, built by a check that fails on a broken link, an orphan page or a missing code (R-BUILD-05, AC-BUILD-16) | M4.5; owner |
| D-74 | **0.1.0 grows to M8.** 0.1.0 also carries RFC 0001 §10's "0.2: Parity and loopback" row: the §6 parity rules, `loopback` mode, the by-value proxy, latency and fault injection and the mode matrix (M5), Observation tags, context propagation and outgoing authentication (M6), plus the retry, circuit-breaker and idempotency work D-16 deferred (M7); M8 hardens and publishes. Amends R-ROAD-01, R-ROAD-02, the 0.1 exclusions and the RFC coverage table of `delivery/62`, and D-16's "beyond 0.1" (M7.1 writes the rules that replace it). RFC 0001 is unchanged: its §10 rows still name the original release order | 0.1.0 is not yet published, and parity before extraction (INV-6) is what sets Topolith apart from Spring Modulith alone; shipping it later would make the first release the one without it | Owner, 2026-11-26 |
| D-75 | **A loopback module is internal for activation; its callers get an HTTP client.** It is imported, persisted, migrated and checked exactly as an internal module, and INV-2's module that contributes nothing is one that is neither `internal` nor `loopback`. Topolith registers an HTTP service group `<id>` for its provided contracts, as for an external module, and registers each provider bean that implements a provided contract as a non-default candidate, so a by-type injection of the contract resolves to the client alone; the provider is not a second provider for TL0005. Amends INV-2's wording (R-MODE-06, R-EXT-09) | RFC 0001 §3.2 defines loopback as "the module runs in-process" with every call through real HTTP; the module must be activated to serve the calls, and both the bean and the client implement the contract, so one of them has to stop being a by-type candidate without a `@Primary` or a fallback (INV-4). Keeping the provider a bean keeps its routes and R-EXT-07's boundary | RFC 0001 §3.2; M5.1; Amended by D-81 |
| D-76 | **Loopback serves the module's routes on the host's own server and is allowed in any host.** Its routes are mapped under `/<id>` as for an exposed module whatever the `expose.enabled` default, and an explicit `false` fails with TL0003. The base URL is the started servlet server's local port under `server.address` (a wildcard becomes `localhost`, an IPv6 address is bracketed) and the context path; a call before the server listens raises `ModuleUnavailableException` with D-55's kind `connection failure`, as for a remote that is not listening. No listening servlet server (a non-web application, a `MOCK` test environment, `server.port=-1`) or a server that uses SSL by Boot's resolved setting (`server.ssl.bundle` included) fails with TL0026; a reactive application fails with TL0028. Topolith adds no code and logs no warning for loopback outside tests | The calls need a real route and a real port; a `MOCK` environment has neither, so failing names the fix (`RANDOM_PORT`). Plain HTTP keeps the client free of trust-store setup. A host cannot be told apart as test, development or production reliably, and the mode is written explicitly per module (INV-3), so a warning would only add noise to every test run. Reusing TL0026 ("no usable client") and TL0028 ("no exposure support") keeps INV-10 without new codes. A loopback-only failure kind would be an observable difference between modes (INV-6), so none is added; checking SSL by Boot's resolved setting catches a host that sets only a bundle at startup rather than on every call | M5.1; Amended by D-82 |
| D-77 | **A loopback module's client settings are restart-only.** A refresh changing its timeouts, `remote.http-interface` or `loopback.*` keys is not applied; it logs TL0010 naming the module and the key and marks restart pending until the key again matches its running value; a mode change to or from `loopback` is a mode change (D-11, TL0010). R-VAL-04 reads no remote version for a loopback module | The client targets the host itself, so there is no endpoint to repoint at runtime; fault and latency settings are test inputs fixed per context. Topolith already watches `EnvironmentChangeEvent` for its own keys, so ignoring the change silently would be weaker than R-MODE-05; TL0010's meaning ("a refreshed change needs a restart") covers it. The provider is on the host's classpath, so its own descriptor is the version to compare | M5.1 |
| D-78 | **Fault injection lives in the loopback client.** Keys `topolith.modules.<id>.loopback.latency`, `fault.rate`, `fault.kind` (`connection-failure`, `unavailable`, `timeout`) and `fault.seed`. A fault is raised as the transport failure it stands for and goes through R-EXT-04's translation, so it surfaces as `ModuleUnavailableException` (D-55) with the real kind; latency is added after the provider answers and counts against the read timeout. Failing calls are drawn in call order from a sequence fixed by the seed; `topolith-spring-test`'s listener restarts each loopback module's sequence in `beforeTestMethod` (through an internal-package method, not public API), and without it the sequence runs once per context; concurrent calls draw in no defined order. Any `loopback.*` key on a module that is not loopback, an unknown key or an invalid value fails with TL0003 (R-EXT-10) | Faults that take the real translation path test the caller's handling of what production would raise, not of a test-only exception. A `timeout` fault runs the provider, as a real read timeout may have, which is the case idempotency (M7) has to handle. A seed makes a failing run reproducible, and restarting it per test method keeps a method's failing calls the same whether it runs alone or after others sharing a cached context; deciding per context now and per method later would change which calls fail in adopters' seeded tests. Ignoring the keys on another mode would hide a test that does not test what it says (INV-4) | RFC 0001 §3.2, §6; M5.1 |
| D-79 | **The by-value proxy detects and fails, and is opt-in per test.** RFC 0001 §6's "mutation proxy" is this proxy. `@TopolithByValue` (or the builder's `byValue()`) wraps an internal module's contract beans; arguments and results cross as JSON copies made with the mapper the module's HTTP client would use. It fails with an `AssertionError` when the provider changes an argument during the call, changes an argument copy it kept or an object it returned after the call (both kept and checked at the next call and at the end of the test method), or a value does not survive the round trip; a listener fails the test method even when the code under test catches the error. Declared exceptions are rebuilt as the caller's translation rebuilds them. A caller changing its own original argument or its own copy of a result is not a failure (Q-25); a change on another thread after the test method ends is not seen. `@TopolithByValue` without a JSON HTTP message converter fails context load with `IllegalStateException`, and the proxy never wraps a mock. Opt-in does not yet meet P-2's "on unless relaxed to `warn`" (Q-26); the opt-in is superseded by D-119 (R-TEST-04) | Copying alone would hide the hazard in tests while production internal mode keeps sharing objects; failing names it. Using the HTTP client's mapper makes the copy what external mode would send. An `AssertionError` that a listener also records cannot be swallowed by a `catch (RuntimeException)`. Opt-in keeps existing internal-mode tests unchanged: a default-on proxy would break every test of an internal-only, non-web host (no JSON converter) and wrap `@MockitoBean` contract beans; the matrix turns it on where parity is the point. A provider that keeps an argument and changes it later would change the caller's object in internal mode, so kept arguments are checked as kept results are | RFC 0001 §6; M5.1 |
| D-80 | **The mode matrix is a shared abstract test class with one subclass per mode.** Each subclass selects its mode through R-TEST-01's helper or its host's configuration; `topolith-spring-test` ships no matrix runner. The samples run `samples/payment-tests`' payment tests internal under the by-value proxy and loopback in the monolith host, and external in the hybrid host (R-TEST-05) | A Spring test context is fixed per test class, so a parameterized class cannot switch modes without a runner that rebuilds contexts per parameter; subclasses reuse Spring's context cache and the shape the samples already use (R-TEST-03) | M5.1 |
| D-81 | **In loopback the provider stays a default candidate; a resolver hides it from callers outside the module.** Topolith installs a delegating `AutowireCandidateResolver`, only when a loopback module exists, that excludes the provider bean from a dependency typed as one of the module's provided contracts at an injection point outside the module's `package`, and the group's client from such a dependency inside it (package = module, for production code too, D-85). Callers outside the module, the host included, get the HTTP client; the module's own injections of its contract, injections of the provider's concrete class and collections of its other interfaces get the provider. Amends D-75's mechanism and R-MODE-06 "Callers" | A non-default candidate is skipped by every unqualified by-type injection whatever the requested type (Spring's `QualifierAnnotationAutowireCandidateResolver`), so concrete-class injections inside the module would fail without a TL code and a `List<Spi>` the provider also implements would lose it silently (INV-4); the module's own calls to its contract would leave its transaction, unlike internal mode or its real service deployment. A resolver is a standard extension point (INV-8). Failing every provider with another interface would make loopback unusable for many modules, and `@Fallback` falls back silently | Architect review, M5.1; owner |
| D-82 | **The loopback base URL includes the dispatcher servlet path.** It is `http://<host>:<port><context-path><servlet-path>/<id>`, where `<servlet-path>` is the path of Boot's resolved `DispatcherServletPath` (`spring.mvc.servlet.path`), empty for `/` or when Boot registers none. Amends D-76 (R-MODE-06, AC-MODE-43) | A module's routes are served by the `DispatcherServlet`, so a host that maps it under `/api` serves them at `/api/<id>/...`; leaving the path out would make every loopback call a 404 from the host's own server with no startup check to catch it. Reading Boot's resolved path follows the setting the server itself uses | M5.3 review; owner |
| D-83 | **`Topology.internal()` means internal for activation; `ModuleClients` customizes external and loopback groups.** `Topology.internal()` returns the ids of the `internal` and `loopback` modules, the modules whose implementation runs in the host, and `Topology.idsOf(Mode)` tells the two apart. `ModuleClients.configure(<id>, ...)` accepts an external or a loopback module; any other mode fails with TL0003 naming `mode=external or loopback` (R-MODE-06, R-EXT-06) | D-75 makes loopback internal for every activation path, so every caller of `internal()` (imports, scans, entities, migrations, boundary and persistence checks) needs the loopback ids too; keeping the name avoids a second, easily missed accessor. A loopback module's callers get the same group client as an external module's (R-EXT-09), so its customization applies the same way | M5.3 review; owner |
| D-84 | **D-81's resolver covers injection points; other lookups of a loopback contract are ambiguous by design.** `getBean(Class)` of a loopback module's provided contract fails with Spring's `NoUniqueBeanDefinitionException`, `getIfUnique()` returns null, and `getBeansOfType` returns provider and client. Code outside the module injects the contract or looks the client up as `<id>#<interface>`; a lookup by the provider's bean name still returns the provider. A loopback provider that is `@Primary` or carries `@Priority` (also inherited from a superclass) fails with TL0003, and a provider declared through an interface-typed `@Bean` return is checked by its declared type only, because Spring would pick it for a by-type lookup. A loopback group carries the placeholder base URL `http://localhost/<id>` (AC-MODE-53) so Spring Cloud LoadBalancer's group configurer leaves it alone, and only a user-set Boot `base-url` is TL0003. Amends D-81, D-77 (a refreshed `remote.url` or `remote.service-id` logs TL0010) and R-MODE-06 "Callers" | Programmatic lookups do not consult `AutowireCandidateResolver`, and the only standard way to make them unique is `@Primary`/`@Priority`/`defaultCandidate`, which INV-4 and D-81 rule out. A primary provider would hand an outside caller the in-process bean silently (INV-6). Without the placeholder, the load balancer treats the host `localhost` as a service id and every loopback call fails | M5 gate |
| D-85 | **"Inside the module" is the module's package, for production code and tests alike; a test class there fails with TL0032.** D-81's resolver decides by the declaring class's package, so a production caller in a loopback module's package gets the provider: package = module, by design. `topolith-spring-test` fails a test class whose package is a loopback module's package or a subpackage (a nested class included) with TL0032 naming the test class and the module, and does not fail one in a sibling package that shares a name prefix or in an `internal`, `external` or `none` module's package. The check runs for each test class when its instance is prepared, not in the context customizer. The resolver is unchanged. The `@MockitoBean` and `@MockitoSpyBean` of a loopback contract also reach the client (AC-TEST-19): `LoopbackConfiguration.Startup` is `PriorityOrdered`, so the resolver is installed before Spring's bean-override post-processor chooses by type. Overriding by the provider's bean name still targets the provider (D-84) (R-MODE-06, R-TEST-05) | A test class under the module's package would silently get the provider, so its "loopback" run would not cross HTTP, a false green for parity (INV-4 spirit, INV-6); failing names the fix, and silently treating the test as outside would hide a rule that production code follows. The cached context is shared by test classes of other packages, so a customizer check would run for the first class only. Installing the resolver after the override post-processor made a by-type override of a loopback contract fail with "found 2 beans" | M5 gate; owner |
| D-86 | **One `topolith.module.call` observation per contract call, on the caller's side.** External and loopback calls are observed around the group's client call (after R-EXT-04's translation, R-EXT-10's injection inside it); internal calls by an advice on the provider bean in front of R-EXT-07's boundary, installed with or without a transaction manager. A loopback provider gets no advice, and an exposed internal provider's handler invocation for a module-route request is not observed: Topolith marks the request with its handler when route handling starts, and the advice checks the mark before any stack walk; calls the handler makes are observed. The call runs in the observation's scope, so Spring's `http.client.requests` is its child; trace headers are Micrometer Tracing's (R-EXT-11, AC-EXT-42, AC-EXT-45, AC-EXT-46) | Only the caller's side exists in every mode: in internal mode caller and provider share one call, and in external mode the provider is another process, so a provider-side observation would count a loopback call twice and leave the external caller's metrics without it. An exposed internal module's route handler is the advised provider bean, and its stack holds only container and Spring frames, so without the mark the provider host would record a second, `_host`-tagged observation of a call its caller already counted (M6.1 review). Nesting under the caller's observation keeps Spring's HTTP observations as children rather than siblings, and W3C trace propagation is already Micrometer Tracing's job (RFC 0001 §2: only Micrometer Observation, no custom protocol) | M6.1, 2026-11-27 |
| D-87 | **Keys: `module.id`, `module.mode`, `caller.module`, `contract`, `method` and `exception`, all low-cardinality; span name `<module.id> <contract simple name>.<method>`.** `contract` is the contract's fully qualified name and `method` its method name. `exception` is `none`, the simple name of the declared class the caller receives (R-EXT-04's declared class: the thrown class in an api package, else its nearest non-abstract superclass there), or `undeclared`. The observation's error is the exception the caller receives (R-EXT-11, AC-EXT-42, AC-EXT-44) | Contract and method are bounded by the code, and per-method latency is the metric a module call needs, so they are low-cardinality. Normalising `exception` to the declared class gives the same value in every mode, since external mode rebuilds a subclass as its declared ancestor and leaves an undeclared exception's type unspecified (INV-6, D-43); Micrometer's own `error` tag, added by its meter handler from the thrown class, can differ for those two cases (Q-30). A span name carrying the module and method reads well in trace views and is the same in every mode | M6.1, 2026-11-27 |
| D-88 | **`caller.module` comes from a stack walk; a caller outside every module is `_host`; a module's call to its own contract is not observed.** The nearest frame whose class lies in a known module's package (D-85, longest package) names the caller; frames of the JDK, Spring, Micrometer, Topolith's library packages, Mockito, Byte Buddy, the servlet containers (Tomcat, Jetty, Undertow) and generated proxies are skipped, and the first other frame, or none, is host code; a frame in the called contract's own package, such as a default method running on a client proxy, counts as the called module, so the calls it makes are self-calls and only the outer call is observed (INV-6). The walk runs only when the observation is not a no-op (R-EXT-11, AC-EXT-43) | The alternative, a per-caller client or proxy chosen at each injection point, would change bean identity, break D-84's lookups and the AC-TEST-19 mocks, and still miss programmatic lookups; a bounded walk with `StackWalker` costs microseconds, small next to an HTTP call and paid in internal mode only when observing. Module packages are checked first because modules may live under a library-like prefix. A fixed `_host` value keeps the key present in every mode, and no module id can start with `_` (D-27). Self-calls exist only in internal mode, since loopback gives the module's own code the provider (D-81), so observing them would break parity | M6.1, 2026-11-27 |
| D-89 | **The observation convention is public in 0.1.0; there is no Topolith switch and no registry means no-op.** `ModuleCallObservationConvention`, `ModuleCallContext` and `DefaultModuleCallObservationConvention` are `@Incubating` in `io.github.rey5137.topolith.spring.boot.observation`; one host bean replaces the default, a registry's `GlobalObservationConvention` applies as Micrometer defines, and two beans fail with TL0033. Without an `ObservationRegistry` bean nothing is observed; a host turns observation off with an `ObservationPredicate` or Boot's `management.observations.enable.*`. A `final` handler provider of an internal module fails with TL0029 whether or not a transaction manager or an `ObservationRegistry` bean exists; this amends D-47 `ModuleCallContext`'s constructor, `setCallerModule` and `setException` are public only because `ModuleCallObserver` is in another package; they are for Topolith only and may change without notice (owner, 2026-11-27) (R-EXT-07, R-EXT-11, AC-EXT-16, AC-EXT-46, AC-EXT-47) | This is the pattern Spring's own observations follow, so a host renames or adds keys the way it already does for `http.server.requests`. Micrometer Observation comes with Spring Framework, so only the registry bean decides; a second Topolith switch would duplicate Micrometer's. Two conventions would pick a winner silently (INV-4). The advice needs the same class proxy as R-EXT-07's boundary, and a registry exists in nearly every actuator host, so making TL0029 depend on which beans exist would only let a test host without actuator or a datasource pass a provider that production rejects. An interface proxy would hide the handler's routes, and skipping its observation would break INV-6 | M6.1, 2026-11-27 |
| D-90 | **MDC values cross as one `Topolith-Mdc-<key>` header per key listed in `topolith.context.mdc-keys`.** The list is host-wide and empty by default; values are UTF-8 and percent-encoded (RFC 3986 unreserved characters kept); an encoded value is at most 1024 characters, and a longer MDC value is not sent and logs TL0035 naming only the key. The provider matches header names case-insensitively against its own list and stores the listed spelling, and for the call removes every listed key the request does not carry, even one a server filter put (owner, 2026-11-27). Keys are letters, digits, `.`, `_` and `-` starting with a letter or digit, at most 16, distinct ignoring case, and need SLF4J; anything else fails with TL0034. A malformed or over-long inbound header answers 400 without calling the provider. Trace and baggage propagation stay Micrometer Tracing's (R-EXT-12, AC-EXT-48, AC-EXT-50 through AC-EXT-52) | An explicit list sends nothing a host did not choose, since MDC often holds values not meant to leave the process; a header per key is readable in access logs and bounded per key. HTTP header names are case-insensitive and HTTP/2 lowercases them, so the provider's own list restores the case, and keys differing only in case would collide. Percent-encoding carries any MDC string in a header value without charset ambiguity. Micrometer Tracing's baggage needs a tracer, which Topolith does not require; Micrometer context-propagation's accessors move values between threads, not processes, and hold objects rather than strings (Q-29). A value is dropped with a warning rather than truncated, because a truncated value would differ from internal mode silently. Clearing an unsent key gives the provider the null an internal provider sees (INV-6); keeping a server filter's value would differ by mode | M6.1, 2026-11-27; owner |
| D-91 | **The tenant crosses through a small `TenantContext` SPI as `Topolith-Tenant`, and the provider restores MDC and tenant after the request.** `TenantContext` (`current()`, `open(request)` returning a `Scope`) is a public `@Incubating` interface in `io.github.rey5137.topolith.spring.boot`; no bean means no tenant propagation and two beans fail with TL0034. An over-long tenant fails the call with a `TopolithException` carrying TL0038 instead of being dropped. On an internal or loopback module's route handler, Topolith calls `open` with the sent tenant, or null when none was sent so the provider sees no tenant, and with the request's principal (`HttpServletRequest.getUserPrincipal()`: the `Authentication` under Spring Security, null when anonymous); `open` must reject a tenant the principal may not act for by throwing, which answers 403 without calling the provider, and the caller receives R-EXT-04's unspecified exception. Topolith restores MDC and tenant when the request completes; other routes never read the headers (owner, 2026-11-27: clear on provider, host validates; amended by D-98: the check is always installed on a module route, and by D-99: control characters are refused) (R-EXT-12, AC-EXT-48 through AC-EXT-52, AC-EXT-58) | Applications keep the tenant in their own holder (often the one their persistence layer reads), so setting MDC alone would not make it current; an SPI of two methods adapts any holder. It uses JDK types only, but only the HTTP path needs it and business code must need no Topolith type (INV-1), so it lives with the clients rather than in `topolith-core`. Running a provider without its caller's tenant could read another tenant's data, so that case fails loudly (INV-4). Restoring after completion keeps pooled server threads clean, and limiting the headers to module routes keeps them from reaching the host's own endpoints. A loopback module's routes are on the host's public port (D-76), so an authenticated end user could otherwise pick another tenant with a header; making the check part of `open` means a route trusts the header only through the host's decision. The parameter is `java.security.Principal` so Spring Security stays optional (INV-7): Spring Security's servlet integration returns the `Authentication` as the request's principal, and a host without it still gets the container's principal. Opening null when no tenant was sent gives the provider the no-tenant state an internal provider sees (INV-6). `open` takes a `TenantContext.Request` (`tenant()`, `principal()`, `moduleId()`: the module whose route is called). It is a final class that only Topolith constructs, plus `Request.of(...)` for host tests, so a later field adds an accessor without breaking implementors. Internal calls never call `open`, so `moduleId` has no internal-mode counterpart (owner, 2026-11-27) | M6.1, 2026-11-27; owner |
| D-92 | **The outgoing-auth hook is a Spring `ClientHttpRequestInterceptor`, registered per module with `ModuleClients.authenticate(<id>, <interceptor>)`; one hook per module.** It returns a group configurer bean like `ModuleClients.configure` (D-52, D-83) and accepts an external or loopback module; the hook runs after a customizer's interceptors and replaces `auth.type`'s built-in hook. Two hooks for one module, or a hook and an explicit `auth.type`, fail with TL0036; a default `Authorization` header set by a customizer on a module with any hook, relay included, fails startup with TL0036 naming the module, and a request whose `Authorization`, set by an interceptor before the hook, the hook changes fails before it is sent with a `TopolithException` carrying TL0036 (R-EXT-13, AC-EXT-55, AC-EXT-56) | RFC 0001 §6 asks for a hook per HTTP service group; the group is the module, so the hook is keyed by module id. Both built-in hooks are interceptors already (Spring Security's `OAuth2ClientHttpRequestInterceptor` among them), so a new Topolith interface would only wrap Spring's type; keeping Spring Security types out of the signature lets a host without Spring Security add an API key. A customizer cannot replace the interceptor list (D-48), so the hook is its own entry point. Two credential sources for one call would pick a winner silently (INV-4) | M6.1, 2026-11-27 |
| D-93 | **Token relay is the default for every external and loopback module.** `topolith.modules.<id>.auth.type` is `relay` (default), `client-credentials` or `none`. Relay sends `Authorization: Bearer` with the token of an `AbstractOAuth2TokenAuthenticationToken` on the calling thread and sends nothing, without failing, for no `Authentication` or another kind; without Spring Security's resource-server classes no hook is installed. Invalid or misplaced `auth.*` keys fail with TL0003; the keys are restart-only (TL0010, as D-77) (R-EXT-13, AC-EXT-53, AC-EXT-54, AC-EXT-56, AC-EXT-57) | Relay is what makes an extracted module see the caller the internal provider saw, so parity is on by default as P-2 asks; opting in per module would make every first extraction lose authentication silently. A default is not a winner: like R-EXT-03's timeouts it applies only when nothing is configured, while two configured sources fail (D-92). With no token there is nothing to send in any mode, so sending nothing is not a fallback | M6.1, 2026-11-27 |
| D-94 | **Client credentials are in 0.1.0 as `auth.type=client-credentials` with `auth.registration-id`.** The hook is Spring Security's `OAuth2ClientHttpRequestInterceptor` over an `AuthorizedClientServiceOAuth2AuthorizedClientManager` (client-credentials provider only) that Topolith always builds from the single `ClientRegistrationRepository` and `OAuth2AuthorizedClientService` beans, never a host `OAuth2AuthorizedClientManager` bean, authorizing a fixed principal named `topolith:<registration id>`; missing OAuth2 client classes, a missing or unknown registration, or a missing or second repository or service fail with TL0037. A host wanting its own manager uses `ModuleClients.authenticate` (D-92). It relaxes authentication parity deliberately: the provider sees the client's principal in loopback and external mode. "The same `Authentication`" across modes means the same `getName()` and authority set, which parity tests compare (R-EXT-13, AC-EXT-54, AC-EXT-56, AC-TEST-22) | RFC 0001 §6 names client credentials beside relay, Spring Security does the token work, and the cost is two keys and one check. The authorized-client-service manager works outside a servlet request, which a contract call may be (a `@Scheduled` job, an async thread); a host's manager bean is in a servlet application usually a `DefaultOAuth2AuthorizedClientManager`, which Spring Security has published by default since 6.2 and which needs the current `HttpServletRequest`, so preferring it would fail those calls. Authorizing the calling user instead of a fixed principal would request and store one token per user for a grant whose token does not depend on the user, and grow the authorized-client service without bound (M6.1 review). A service principal differs from the user by design, so the relaxation is stated rather than hidden (RFC 0001 §2 principle 4). Object identity, type and credentials necessarily differ once a token is decoded in another process, so only name and authorities can be the same | M6.1, 2026-11-27 |
| D-95 | **A caller without a bearer token, and a loopback host that cannot authenticate one, are documented gaps, not startup failures.** Relay sends nothing for a session login or an anonymous caller, and a host without resource-server configuration does not authenticate a relayed token, so in loopback and external mode the provider gets what its security chain makes of a request without credentials, while in internal mode it sees the caller's `SecurityContext` (R-EXT-13, AC-EXT-53) | Topolith cannot see reliably how a host's `SecurityFilterChain` authenticates a request (a decoder may be configured inline rather than as a bean), so a startup check would be a heuristic with false warnings for hosts that use form login on purpose. The difference is exactly what an extracted module would meet, and loopback is the mode built to show it before extraction (D-75) | M6.1, 2026-11-27 |
| D-96 | **A client-credentials hook's token request uses the host's token client bean, or its module's current timeouts and nothing else of the module's settings.** A client-credentials hook's token request uses the host's single `OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest>` bean when there is one, as Spring Security's own manager does; two fail with TL0037. Otherwise Topolith gives the hook's `ClientCredentialsOAuth2AuthorizedClientProvider` Spring Security's `RestClientClientCredentialsTokenResponseClient`, with the converters and error handler Spring Security's default configures, over a request factory with the connect and read timeouts that module `<id>`'s client currently uses (R-EXT-03, refreshed with it by R-EXT-08) and Boot's built-in `HttpClientSettings.defaults()` for everything else. The module's SSL bundle, redirect settings and `spring.http.clients.*` settings are never applied: the token endpoint is the registration's authorization server, not the module's provider. A token request that fails on I/O, or is answered 5xx or 429, raises `ModuleUnavailableException` with D-43's kind (D-97). A provider response whose OAuth2 error is `invalid_token`, or a token request ending in `invalid_grant`, removes the token from the `OAuth2AuthorizedClientService`; a 401 counts as `invalid_token` unless its `WWW-Authenticate` names another error. The failed call is not retried, and the next call obtains a new token (R-EXT-03, R-EXT-08, R-EXT-13, AC-EXT-56, AC-EXT-59, AC-EXT-60) | Spring Security's default token client has no read timeout, so an unresponsive token endpoint would hang every call of the module whatever its own timeouts say; the token request is part of the call, so the call's limits apply to it, and each hook has its own provider, so the limits are those of the module whose call needed the token. The module's SSL bundle (Boot's `spring.http.serviceclient.<id>.ssl.bundle`) holds the trust and any client certificate for the provider's host, so applying it would (a) replace JVM trust and fail the handshake with a public authorization server, (b) send the module's client certificate to a third party, (c) make the outcome depend on which module fetched a registration's shared token first (INV-4), and (d) differ between loopback, where the bundle usually does not exist, and external mode (INV-6); a new per-module `auth.ssl-bundle` key fails (c) too and duplicates Spring (P-5). The token client bean is Spring Security's documented override point (for `private_key_jwt`, a proxy or custom trust), so ignoring it would pick a winner silently (INV-4). Only the R-EXT-08 rebuild changes the timeouts, never a re-read of the environment, so the token request and the call never drift apart. Removal goes by registration and principal: under a race it costs at most one extra token request, and a declared exception answering 401 without a header (R-EXT-04) also evicts, costing one token request per such call in every mode; a gateway that strips `WWW-Authenticate` would otherwise never evict. Replaying a failed non-idempotent call is not safe, so there is no retry | M6.4, 2026-11-27; architect, owner |
| D-97 | **A failed client-credentials token request is unwrapped only from Spring Security's own exception, and only an outage becomes `ModuleUnavailableException`.** The contract translator looks through the cause chain (up to 8 causes) only when the exception it receives is an `OAuth2AuthorizationException` (found by class name up the superclass chain, so `ClientAuthorizationException` counts and no Spring Security class is imported). A `ResourceAccessException` in that chain, a 5xx response or a 429 raises `ModuleUnavailableException` with D-43's `timeout` or `connection failure` kind, a 5xx also under a circuit breaker's `NoFallbackAvailableException`. An OAuth2 4xx error such as `invalid_client` or `unauthorized_client`, and an empty token response, stay unspecified: the caller gets the Spring Security exception as thrown. A host's own wrapper exception, or any other runtime exception, that holds a `ResourceAccessException` propagates as thrown (D-51); the M6.3 walk that mapped it was wider than D-96 asked and is removed. A token-request factory is built only when Topolith's own token client is used, and the one an R-EXT-08 refresh replaces is closed when it is `AutoCloseable` or `DisposableBean` (R-EXT-04, R-EXT-13, AC-EXT-61) | A token endpoint that is down, overloaded or behind a proxy answering 5xx or 429 is an outage of the called module, so business code that catches `ModuleUnavailableException` for resilience (D-55) must see it without catching an OAuth2 type (INV-1); an OAuth2 4xx error is a configuration error that retrying cannot fix, so it is left to surface as itself. Limiting the walk to Spring Security's wrapper keeps R-EXT-04's rule that a host's wrapper propagates as thrown, and matching it by name keeps Spring Security out of the translator (INV-7). Each unused factory held a client, a pool or a selector thread, and a replaced one was never released | M6 gate, 2026-11-27; owner |
| D-98 | **A host that exposes a module route always checks the tenant header: with no `TenantContext` bean, a request carrying `Topolith-Tenant` answers 500 with TL0039 and the provider does not run.** The provider half is installed on every host with an exposed internal or loopback module, not only when a key is listed or a `TenantContext` bean exists. A request with no tenant header passes on such a host, and MDC headers stay ignorable when no key is listed. The caller receives R-EXT-04's unspecified exception. A host exposing no module and a `none` module get nothing (INV-2). The check costs one pass over the header names of a module route's request. It corrects R-EXT-12's "a provider never runs without the caller's tenant" and TL0038's row, which claimed it (R-EXT-12, AC-EXT-51, AC-EXT-52, TL0039) | A provider extracted without its `TenantContext` bean, or with a misnamed profile, would silently run with no tenant or the server thread's own, a cross-tenant read or write risk that no test of internal mode shows (INV-4: nothing is missing silently). Refusing only when a tenant was sent leaves hosts that never use tenants unchanged | M6 gate, 2026-11-27; owner |
| D-99 | **A decoded `Topolith-Mdc-*` value or tenant with an ISO control character answers 400 on the provider; the caller never sends one.** The caller drops an MDC value with a control character and logs TL0035, and fails a call whose tenant has one before sending, with TL0038 (so the provider cannot run for the wrong tenant). The provider's 400 extends AC-EXT-50. The provider's interceptor is registered at the highest precedence so a host's own interceptors see the sent values (R-EXT-12, AC-EXT-50, AC-EXT-51) | Any client that reaches a module route can send the headers (R-EXT-12 Trust), and a decoded newline in MDC forges log lines. The cost is a stated parity exception: an internal provider sees the raw value | M6 gate, 2026-11-27; owner |
| D-100 | **Idempotency is declared explicitly with `@Idempotent` on an abstract contract method or contract interface and recorded as `idempotent=<contract>#<method>` in `format-version=1` descriptors, verified at startup with TL0040; a caller can also opt into retrying RFC 9110 safe HTTP methods per module with `remote.retry.safe-methods-idempotent=true` (default `false`).** `@Idempotent` (`io.github.rey5137.topolith.contract.Idempotent`, `topolith-core`, `@Incubating`, `@Documented`, `RUNTIME` retention, targets `TYPE` and `METHOD`, no members) is the only way to record a contract method in the descriptor's `idempotent` set; HTTP exchange annotations (`@GetExchange`, `@PutExchange`, `@DeleteExchange`, `@HttpExchange`) never enter the descriptor. On a provided contract interface `C`, a public abstract (non-`default`, non-`static`, non-`private`) interface method (`Object` methods excluded) is idempotent when `C` carries `@Idempotent`, or when the method declaration `C` exposes (on `C` if `C` declares or overrides it, else on the superinterface from which `C` inherits it) or that declaration's enclosing interface carries `@Idempotent`; a `default` method runs locally on the proxy and is never an HTTP exchange. Overloaded abstract methods of one name on `C` must agree on whether they are idempotent. The processor writes the sorted `<contract binary FQN>#<method name>` entries to `idempotent` in both `apis/<id>.properties` and `modules/<id>.properties` (`ApiDescriptor.idempotent()`, `ModuleDescriptor.idempotent()`) and fails with TL0013 when `@Idempotent` is on a non-interface, a type outside `api-packages`, a `default`, `static` or `private` interface method, or disagreeing overloads, or when a module build's classpath API differs. An absent or empty `idempotent` key parses to an empty set under `format-version=1`, so existing descriptors remain valid; a duplicate or malformed entry or a contract not in `provides` fails with TL0013. At startup, for every `internal`, `loopback` or `external` module (`none` is not checked, INV-2), a mismatch between the descriptor's `idempotent` set and the loaded contracts' `@Idempotent` annotations, a mismatch between an internal or loopback module's module and API descriptors on `idempotent`, `@Idempotent` on a `default` method or disagreeing overloads on a contract, or `@Idempotent` on a separate `remote.http-interface` (D-49) fails with TL0040. On the caller, `topolith.modules.<id>.remote.retry.safe-methods-idempotent` (boolean, default `false`) optionally treats an unannotated exchange whose resolved HTTP method on the caller's exchange interface (the contract itself, or a D-49 separate `remote.http-interface`) is `GET`, `HEAD`, `OPTIONS` or `TRACE` (RFC 9110 §9.2.1) as idempotent for retry; `PUT`, `DELETE`, `POST` and `PATCH` are never retried without `@Idempotent`. Supersedes D-16 in part (R-DESC-02, R-DESC-03, R-DESC-05, R-EXT-14, AC-DESC-11 through AC-DESC-13, AC-EXT-62, AC-EXT-69) | Under D-49 a contract in `*-api` need not carry `@HttpExchange`, adopted legacy services often have side-effecting `GET`/`PUT`/`DELETE` routes or idempotent `POST` queries and commands, and the M7 exit requires that a non-idempotent call is never retried (INV-4); defaulting `safe-methods-idempotent` to `false` keeps retry explicit by default while letting a caller opt into RFC 9110 safe-method retry per module without touching `*-api` or risking `PUT`/`DELETE` side effects. Rejecting `@Idempotent` on a `default` method prevents retrying a local proxy method that calls a non-idempotent exchange. Living in `io.github.rey5137.topolith.contract` keeps INV-1 and D-54; `RUNTIME` retention lets both javac and startup reflection read the annotation. Keeping `idempotent` in `format-version=1` without bumping the format version (D-39) is safe only because `0.1.0` is unreleased, so no published `format-version=1` reader exists to reject the key | RFC 0001 §5; M7.1, 2026-11-27; owner; Q-8 |
| D-101 | **Per-group retry is off by default (`max-attempts=1`), wired in `topolith-spring-cloud` over Spring Framework 7's `org.springframework.core.retry`, and retries only idempotent calls on transient provider transport failures.** Configured per `external` or `loopback` module under `topolith.modules.<id>.remote.retry`: `max-attempts` (int in `[1, 10]`, default `1`), `initial-backoff` (duration `>= 0`, default `100ms`), `max-backoff` (duration `>= initial-backoff`, default `max(1s, initial-backoff)`), `multiplier` (double `>= 1.0`, finite, default `2.0`) and `safe-methods-idempotent` (boolean, default `false`), where wait before retry $k \ge 1$ is $\min(\text{max-backoff}, \text{initial-backoff} \times \text{multiplier}^{k-1})$. Retry applies per abstract contract method exchange when `max-attempts > 1` and the method is in `idempotent` or eligible through `safe-methods-idempotent=true` (D-100); a non-idempotent exchange makes at most one attempt. Retryable outcomes are an allowlist of four provider-hop transport failures (D-105): connection failures (`ResourceAccessException` other than timeout, including R-EXT-10 `connection-failure`), connect or read timeouts (`ResourceAccessException` timeout, including R-EXT-10 `timeout` or latency timeout), circuit-breaker time-limiter `TimeoutException` (under Spring Cloud's global http-services breaker), and HTTP 502, 503 or 504 responses without a Topolith `urn:topolith:problem:contract-exception` body (including R-EXT-10 `unavailable`). Every other outcome is not retried: any Topolith contract-exception problem (a declared exception, a provider's own `ModuleUnavailableException` problem, or an unrebuildable class), any 4xx (including 401 with `client-credentials`, D-96, and 429), a 5xx without a Topolith problem other than 502, 503 or 504 (such as 500), zero LoadBalancer instances (`no instance`), an open circuit (`CallNotPermittedException`), a client-credentials token-request failure (`OAuth2AuthorizationException`, D-97), a call-time `TopolithException` (`TL0036`, `TL0038`), or any other exception. Tuning keys (`initial-backoff`, `max-backoff`, `multiplier`, `safe-methods-idempotent`) are allowed when `max-attempts` is unset or `1` (validated, ignored while off). Setting `remote.retry.*` on an `internal`, `none` or unknown module, an unknown key, an invalid value (an explicit `max-backoff` below `initial-backoff` included), or `max-attempts > 1` alongside `spring.cloud.circuitbreaker.http-services.enabled=true` fails with TL0003; `max-attempts > 1` without `topolith-spring-cloud`'s resilience configuration (which is not gated on `EnvironmentChangeEvent` or `spring-cloud-context`) fails with TL0041. Amended by D-105; supersedes D-16 in part (R-EXT-14, AC-EXT-62 through AC-EXT-64, AC-EXT-67, AC-EXT-69) | Defaulting retry off avoids surprising replay latency unless a host asks for it (INV-4), allowing validated tuning keys while `max-attempts=1` lets an operator disable retry with a single key during an incident, and defaulting `max-backoff` to `max(1s, initial-backoff)` avoids failing an unset key when `initial-backoff` exceeds `1s`. Spring Framework 7's `org.springframework.core.retry` is already in `spring-core`, while wiring it in `topolith-spring-cloud` (independent of `spring-cloud-context`) matches RFC 0001 §5 and M7.3 without a silent skip when `spring-cloud-context` is absent. A provider's Topolith problem naming `ModuleUnavailableException` means the provider already ran and threw from its own downstream call, which may not have been idempotent, so it is never retried; an open circuit or empty LoadBalancer cache cannot be healed by immediate local retry | RFC 0001 §5; M7.1, 2026-11-27; owner; Q-6 |
| D-102 | **Per-group circuit breaker is off by default (`enabled=false`), wired in `topolith-spring-cloud` over Spring Cloud CircuitBreaker inside retry and without fallback; declared exceptions and provider `ModuleUnavailableException` problems do not trip the breaker and survive it.** Configured per `external` or `loopback` module under `topolith.modules.<id>.remote.circuit-breaker`: `enabled` (boolean, default `false`), `failure-rate-threshold` (float in `(0, 100]`, default `50`), `sliding-window-size` (int `>= 1`, default `100`), `minimum-number-of-calls` (int in `[1, sliding-window-size]`, default `min(10, sliding-window-size)`; an explicit value above the window fails TL0003) and `wait-duration-in-open-state` (duration of at least `1ms`, default `60s`). Each attempt runs on the calling thread with no time limiter and no bulkhead, governed by R-EXT-03's connect and read timeouts. Only provider-hop transport and availability failures record as breaker failures (D-105), whereas every Topolith problem response (a declared exception, a provider's own `ModuleUnavailableException`, or an unrebuildable class), any 4xx, a failed token request, `TopolithException` (`TL0036`, `TL0038`) and any other exception are ignored, and declared exceptions unwrap from `NoFallbackAvailableException` through `ContractCallTranslator` as their declared type (D-51); an open-circuit rejection (`CallNotPermittedException` with Resilience4j) becomes `ModuleUnavailableException` with kind `circuit open`. Retry runs outside the circuit breaker so each attempt enters the breaker and an open circuit stops retry immediately. Threshold keys are allowed when `enabled` is unset or `false` (validated, ignored while off). Topolith installs no fallback, and a host `@HttpServiceFallback` targeting a module group, `minimum-number-of-calls` above the effective `sliding-window-size`, `enabled=true` alongside `spring.cloud.circuitbreaker.http-services.enabled=true` or another source configuring breaker `<id>` (D-105), or an invalid or misplaced `remote.circuit-breaker.*` key fails with TL0003; `enabled=true` without `topolith-spring-cloud` and exactly one supported `CircuitBreakerFactory` bean fails with TL0041. Amends D-51; amended by D-105; supersedes D-16 in part (R-EXT-04, R-EXT-15, AC-EXT-65, AC-EXT-67) | A declared exception or a provider's downstream `ModuleUnavailableException` problem is an HTTP response from a running provider and never opens a circuit for the whole provider module in internal mode, so ignoring it in the breaker preserves parity (INV-6) while D-51's unwrapping preserves its exception type (M7 exit). Running retry outside the breaker lets each failed attempt count toward the window and stops retries as soon as the circuit opens, and running on the calling thread with no time limiter or bulkhead keeps R-EXT-03's 5s default read timeout and R-EXT-11's parent observation scope intact. Rejecting `minimum-number-of-calls > sliding-window-size` avoids Resilience4j's silent cap (INV-4) | RFC 0001 §5; M7.1, 2026-11-27; owner; Q-6 |
| D-103 | **Retry and circuit breaker preserve mode parity, the single `topolith.module.call` observation, loopback fault order, context headers and outgoing auth.** An `internal` module has no transport and installs neither retry nor a circuit breaker; a `loopback` module's group client gets the same per-group retry and circuit breaker as an `external` module's; a `none` module registers no retry or circuit-breaker bean, configurer or decorator (INV-2). In `loopback` mode, each retry attempt draws the next entry from the module's R-EXT-10 seeded fault sequence (for a `timeout` fault on an idempotent call, the provider ran on the timed-out attempt and runs again on the retry). `ModuleCallObserver` (R-EXT-11) sits outside `ContractCallTranslator` and retry, so one contract call records exactly one `topolith.module.call` observation whose `exception` tag reflects the call's final outcome (`none` when a retry succeeds), while each HTTP attempt records its own `http.client.requests` child observation under it. Listed MDC headers, `Topolith-Tenant` (R-EXT-12) and the relayed bearer token (`auth.type=relay`, R-EXT-13) captured on the calling thread are sent on every attempt (R-EXT-14, R-EXT-15, R-TEST-05, AC-EXT-63, AC-EXT-66, AC-TEST-24) | A retry is an implementation detail of one contract call's transport, so recording one `topolith.module.call` observation per contract call matches internal mode's call count (INV-6) while the child `http.client.requests` observations still show every HTTP attempt in traces and metrics. Drawing from the R-EXT-10 fault sequence per attempt lets loopback tests verify that idempotent methods recover from transient faults and tolerate duplicate execution on a read timeout while non-idempotent methods never replay. Capturing context and relay attributes on `HttpRequestValues` before the exchange adapter runs keeps every retry attempt authenticated and tenant-scoped | RFC 0001 §5, §6; M7.1, 2026-11-27 |
| D-104 | **An `external` module's `remote.retry.*` and `remote.circuit-breaker.*` settings are rebuilt independently on refresh; a `loopback` module's are restart-only.** With `topolith-spring-cloud`, an `EnvironmentChangeEvent` on an `external` module rebuilds only the changed part of the module's client: changing `remote.retry.*` validates and swaps only the group's retry policy (leaving the running circuit breaker and its open/half-open/closed state untouched); changing `remote.circuit-breaker.*` validates and rebuilds only the group's circuit breaker (resetting the rebuilt breaker to closed with an empty window under the new thresholds, or removing it when `enabled` becomes `false`), while changing only URL, service id or timeouts leaves both the retry policy and the circuit breaker untouched; calls already in flight finish on the previous policy and breaker (D-12, D-50). Tuning and threshold keys remain allowed when retry or the breaker is turned off on refresh (Owner decision 4). If the refreshed settings are invalid (any TL0003 or TL0041 condition) or the rebuild fails, Topolith keeps the running retry policy and circuit breaker unchanged, logs warning TL0011 and reports the failure on the `topolith` actuator endpoint (D-14). On a `loopback` module, `remote.retry.*` and `remote.circuit-breaker.*` are restart-only like its other client settings (D-77): a refresh changing one leaves the client unchanged, logs warning TL0010 naming the module and the key, and marks restart pending until every such key again matches its running value (D-13). Answers Q-23; amends D-12 and D-51; supersedes D-16 in part (R-EXT-08, R-EXT-14, R-EXT-15, AC-EXT-68) | Retry and circuit-breaker thresholds are operational tuning like timeouts, not topology (INV-9), and D-12 originally included them in external client refresh; swapping the retry policy and the circuit breaker independently prevents a retry or timeout tweak during an outage from resetting an open circuit breaker to closed and flooding a failing provider, while allowing tuning keys while off lets an operator flip `enabled=false` or `max-attempts=1` alone. Loopback targets the host's own server with fixed per-context test inputs, so keeping its resilience keys restart-only matches D-77 | M7.1, 2026-11-27; owner; Q-23 |
| D-105 | **Retry, breaker and translation share one allowlist outcome classification; only provider-hop transport and availability failures trip the breaker; Topolith owns breaker `<id>` on exactly one supported `CircuitBreakerFactory` (0.1: Resilience4j).** The circuit breaker records a failure only for a provider-hop transport or availability failure — (1) connection failure, (2) connect, read or time-limiter timeout, (3) any 5xx response without a Topolith `urn:topolith:problem:contract-exception` body (502, 503, 504 and any other 5xx such as 500 without a Topolith problem), or (4) zero LoadBalancer instances — and records a success on a normal response; every other outcome is explicitly ignored (neither failure nor success), including any Topolith problem response (a declared exception, a provider's own `ModuleUnavailableException` problem with a downstream `moduleId()`, or an unrebuildable class), any 4xx response (401 `invalid_token` eviction and 429 included), a failed client-credentials token request (`OAuth2AuthorizationException`, D-97), a `TopolithException` (`TL0036`, `TL0038`) and any other exception. Retry uses the subset of rows (1)–(3) and 502/503/504 without a Topolith problem after unwrapping one `NoFallbackAvailableException` (by class name, as in D-51), stopping immediately on an open-circuit rejection (`CallNotPermittedException`). The five `remote.circuit-breaker.*` keys are implementation-neutral, and unexposed settings stay at the implementation's documented defaults from a fresh builder; in 0.1 the only supported factory is `Resilience4JCircuitBreakerFactory`, wired through a package-private adapter in `topolith-spring-cloud` (no public SPI; another implementation is added later as an adapter with no key or rule change, Q-31). `remote.circuit-breaker.enabled=true` requires `topolith-spring-cloud`'s resilience configuration and exactly one supported `CircuitBreakerFactory` bean; zero, two or more, or an unsupported implementation fails with TL0041 naming the module and beans found. Another source configuring breaker `<id>` (a Spring Cloud `Customizer`/`configure` for `<id>`, `resilience4j.circuitbreaker` or `resilience4j.timelimiter` `instances.<id>`/`configs.<id>`, or `spring.cloud.circuitbreaker.resilience4j.disable-time-limiter-map.<id>`), a breaker named `<id>` already in `CircuitBreakerRegistry`, an active Resilience4j bulkhead provider on the factory (`spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled=false` fix-it), or `spring.cloud.circuitbreaker.http-services.enabled=true` (when `enabled=true` or `max-attempts > 1`) fails with TL0003. Each attempt runs on the calling thread with no time limiter and no bulkhead. Amends D-51, D-101 and D-102 (R-EXT-04, R-EXT-14, R-EXT-15, AC-EXT-64, AC-EXT-65, AC-EXT-67) | Recording a provider's downstream `ModuleUnavailableException` problem opens the caller's circuit for the entire provider module and changes the caller-visible `moduleId()` from the downstream module to the provider for a fault internal mode localizes (INV-6). Resilience4j records every non-ignored exception by default, and host customizers, pre-existing registry entries and Spring Cloud's default-on Resilience4j bulkhead each silently alter thresholds, exception types, concurrency or the calling thread (breaking R-EXT-11's parent observation scope) if not rejected (INV-4, INV-6). Unwrapping one `NoFallbackAvailableException` in the shared classifier lets retry classify attempt failures when the breaker wraps the exchange inside retry. Neutral keys and a package-private adapter keep a second implementation additive without freezing a public SPI in 0.1 | M7.1 fix round 1, 2026-11-27; architect, owner |
| D-106 | **While any module has retry (`max-attempts > 1`) or its circuit breaker enabled, Topolith defaults Spring Cloud's global `spring.cloud.circuitbreaker.http-services.enabled` to `false`; an explicit `true` stays TL0003.** The default is a lowest-priority property, so a host's own value wins. Without a module asking for retry or a breaker the key is left alone. Spring Cloud applies its global breaker only at startup: the key is read by a condition, and nothing reads a refreshed value. So validation uses the running state, whether Spring Cloud's HTTP-service breaker configurer (`CircuitBreakerRestClientHttpServiceGroupConfigurer`) is in the context, and not the key's current value. At startup, that configurer together with a module whose retry or breaker is on fails with TL0003, even without an explicit `=true`. On refresh, turning an `external` module's retry or breaker on while the configurer is in the context does not take effect: retry and the breaker stay off, Topolith logs TL0010 naming the module and the key, and marks restart pending until both are off again (D-13); on the next start the default above applies (R-EXT-08, R-EXT-14, R-EXT-15, AC-EXT-67, AC-EXT-68) | Spring Cloud enables that global breaker when the key is unset and a `CircuitBreakerFactory` bean exists, and it would wrap every group, Topolith's included, on another thread with a time limiter, breaking R-EXT-15's calling-thread rule and R-EXT-11's observation scope. Failing on an unset key would force every adopter to set it; an explicit `true` is a stated conflict | M7.3, 2026-11-27; owner. Amended M7.4, 2026-11-27, architect ruling |
| D-107 | **A contract call captures its retry policy and circuit-breaker instance once, when it starts, and uses them for every attempt.** A refresh affects only calls started after the swap. The breaker is bound by instance, not by a registry-name lookup per attempt, so an attempt in flight never records into a rebuilt breaker or re-creates a removed one. Clarifies D-104 (R-EXT-14, R-EXT-15, AC-EXT-65, AC-EXT-68) | D-104 and AC-EXT-68 say calls in flight finish on the previous policy and breaker; AC-EXT-65 counts one failure or success per call and stops a retry when the circuit opens, and D-104's rebuilt breaker has an empty window only if old calls cannot record into it | M7.4, 2026-11-27; architect ruling |
| D-108 | **Removing or narrowing `@Idempotent` is a breaking contract change; adding it is compatible.** A caller decides retry eligibility from its own API descriptor's `idempotent` set (D-100, D-101), so removing `@Idempotent` from a contract method or interface, or adding a non-idempotent overload, requires an `api-version` major bump (R-VAL-04 then warns TL0023 on skew). A caller on an older API jar would otherwise retry a now non-idempotent call, and the cross-version check is the only signal Topolith has (INV-6). Amends D-100, R-DESC-05. | M7 gate, 2026-11-27; architect ruling, owner accepted |
| D-109 | **`io.github.rey5137.topolith.spring.boot.internal` is the internal seam between Topolith's Spring artifacts.** It holds the types topolith-spring-test and topolith-spring-cloud share with topolith-spring-boot (fault sequences, D-78; resilience settings, modules, refresh hook and outcome classification, D-101 through D-107). It may depend only on the JDK, topolith-core, Spring Framework and Boot, never on Spring Cloud classes except by name (INV-7). It is not public API despite the `@Incubating` that AC-BUILD-15 requires; an ArchUnit rule (AC-BUILD-17) confines its dependents to those three artifacts. Q-31 decides whether any of it becomes a public SPI. Amends D-78. | M7 gate, 2026-11-27; architect ruling, owner accepted |
| D-110 | **A consumer-implemented SPI interface is marked `@ConsumerImplemented`; a contract provided only by a `@Bean` method is listed in the module declaration's `provides`.** `io.github.rey5137.topolith.contract.ConsumerImplemented` (`@Incubating`, RUNTIME, TYPE) on an interface in an api package leaves it out of the API's and the module's `provides`, and a class implementing it is not its provider; both sides drop marked interfaces before keeping the most-derived, so an unmarked supertype stays a contract unless marked too. `@TopolithModule.provides` (`String[]`, default empty) on a module declaration adds contracts that no concrete class under the package implements; each entry must be in the API's inferred `provides` and have no implementing class, and the attribute on an API declaration is a compile error. At startup an internal module needs exactly one bean for each listed contract among the definitions it contributes (several: TL0005; none: TL0044). A listed contract's bean is owned by its definition, so R-EXT-07's boundary and R-EXT-11's observation apply whatever its class. Before M8.6 a listed bean had no route and failed with TL0044 when loopback or exposed. M8.6 registers routes for listed beans (D-125), relaxing that failure compatibly (D-27). The descriptor format is unchanged. Amends D-29 (R-DESC-02, R-DESC-03, AC-DESC-14 through AC-DESC-16) | The marker states the SPI on the type, so the API and every module read one fact without a list that can drift; only a provider the processor cannot see needs a list. INV-1 holds because the marker is in the `contract` package | M8.1, 2026-11-27; owner; Q-11 (RFC 0002) |
| D-111 | **Excluding a Topolith auto-configuration that activates modules, while a module is `internal` or `loopback`, fails startup with TL0042.** An `AutoConfigurationImportListener` registered in `META-INF/spring.factories` reads the exclusions of each `@EnableAutoConfiguration` import; any auto-configuration that activates modules among them (`TopolithAutoConfiguration` and the `Jpa`, `JpaRepositories`, `JdbcRepositories`, `Flyway` and `Liquibase` `*ModulesAutoConfiguration` classes of `topolith-spring-boot`), with an internal or loopback module in the resolved topology, fails naming the excluded class; excluding one that activates no module, such as `topolith-spring-cloud`'s resilience configuration, is not checked (TL0041 covers it). The check resolves the topology for itself and registers nothing. A test slice that does not include Topolith's auto-configuration is not an exclusion and is not checked. Answers the rest of D-61 (R-MODE-02, AC-MODE-55) | Only an explicit exclusion is checked, so a slice test never misfires; the import filter keeps running without the auto-configuration, so the failure was silent | M8.1, 2026-11-27; owner; Q-13 (RFC 0002) |
| D-112 | **Topolith runs a Pattern A module's migrations on the module's own DataSource when the module declares no runner.** A module with scripts under `db/modules/<id>`, exactly one DataSource bean of its own and no Flyway, `FlywayMigrationInitializer` or `SpringLiquibase` of its own gets Topolith's module runner on that DataSource, with the same tracking tables, order and limits as on the host's. A runner of its own still owns its migrations (P-2); several module DataSources and no runner fail with TL0020. Amends D-32's Pattern A (R-MODE-04, AC-MODE-56, AC-MODE-57) | Removes the one piece of boilerplate Pattern A still needed; Topolith acts only when the DataSource is unambiguous (INV-4) | M8.1, 2026-11-27; owner; Q-16 (RFC 0002) |
| D-113 | **Topolith registers each internal module's Spring Data R2DBC and MongoDB repositories and MyBatis mappers, as it does JPA and JDBC.** One auto-configuration per store, each `@ConditionalOnClass` on its store, scans only internal and loopback module packages, backs off for a module's own registration and restores the host's scan as the JDBC registrar does. MyBatis mappers bind to the host's single `SqlSessionFactory`; several, none of them the module's, fail with TL0024. Amends D-36 (R-MODE-04, AC-MODE-58 through AC-MODE-60) | Leaving any store behind keeps TL0024 as a trap for its adopters; registration is tested at the bean-definition level, so no database server is needed | M8.1, 2026-11-27; owner; Q-17 (RFC 0002) |
| D-114 | **A host's `mybatis.mapper-locations` that reaches a module's resource fails startup with TL0043, in every mode.** The locations are resolved at startup; a resource owned by a module (D-61's resource ownership) fails naming the location and the resource. A module's mapper XML lives beside its mapper interface, where MyBatis loads it. The standalone module (`topolith.standalone`) is exempt, as in TL0018: there the module is the application (R-MODE-04, AC-MODE-61) | TL0020's host-location rule applied to MyBatis: a `none` module's XML would otherwise load (INV-2), and a host that starts in one topology starts in all of them | M8.1, 2026-11-27; owner; Q-18 (RFC 0002) |
| D-115 | **A module declaration may have no api packages.** `@TopolithModule.api()` defaults to empty. A module declaration without api packages writes a module descriptor without `api-packages`, `provides` or `idempotent`, and no API descriptor; `api-version` stays required. An API declaration still needs at least one package. `external` or `loopback` for such a module fails with TL0003 (nothing to call); `internal` and `none` apply as usual. Amends D-27 and D-29 (R-DESC-01, R-DESC-04, R-MODE-01, AC-DESC-17, AC-DESC-18, AC-MODE-62) | A listener-only or job module no longer needs an API artifact holding a placeholder type; relaxing the descriptor stays compatible (D-27) | M8.1, 2026-11-27; owner; Q-19 (RFC 0002) |
| D-116 | **A contract method may declare checked exceptions in its `throws` clause; they cross as declared exceptions.** A checked exception class named in a contract method's `throws` clause must itself lie in an api package and meet the same public-class, constructor and `@ResponseStatus` rules as an unchecked one; a thrown subclass outside the api packages is rebuilt as its nearest api-package ancestor, which the method declares, and thrown by the caller. TL0027 no longer rejects a declared checked exception, only a named class outside the api packages (even one extending an api-package exception) or one that cannot be rebuilt. An exception's fields beyond its message and its cause chain still do not cross; a payload (RFC 0002 Q-21 option C) stays open for a later release. Amends D-43 (R-EXT-04, AC-EXT-70, AC-EXT-71) | Checked exceptions are common in contracts written before extraction; the payload's mapper rules are the least settled part and can be added compatibly | M8.1, 2026-11-27; owner; Q-21 (RFC 0002) |
| D-117 | **A 401, 403, 404, 405 or 415 that the module's handler did not produce becomes `ModuleUnavailableException`; `IllegalArgumentException`, `IllegalStateException` and `UnsupportedOperationException` are declared.** Topolith sets the response header `Topolith-Module: <id>` before a module route's handler runs (after the tenant check), so a status without a Topolith problem and without the header (a missing route, a method or media type the route does not take, the host's security chain, a tenant rejection) is a topology failure, while the same status with the header came from provider code (`ResponseStatusException`, method security) and stays undeclared, as in internal mode. The new kinds are `status 401`, `status 403`, `status 404`, `status 405` and `status 415`; their outcome is D-105's ignored one (no retry, no breaker failure). A `TenantContext` rejection (403) therefore reaches the caller as `ModuleUnavailableException`. The three JDK classes, and their subclasses as their nearest listed class, are declared in every module and cross with their message. Amends D-43, D-98 (R-EXT-04, R-EXT-12, AC-EXT-72 through AC-EXT-74) | These statuses are failures only the topology adds, so they get the type every caller already handles; the JDK exceptions crossed in internal mode and not in external mode (INV-6), and their message is what an internal caller already sees | M8.1, 2026-11-27; owner; Q-22 (RFC 0002) |
| D-118 | **`TopolithTestSupport.awaitVersionChecks(ApplicationContext, Duration)` waits for the remote version check; a closed context stops it.** The helper (public, `@Incubating`, `topolith-spring-test`) reaches the check through the D-109 seam and returns whether it finished in time. Context close interrupts the check thread and drops any TL0015 or TL0023 line after close (R-VAL-04, R-TEST-06, AC-VAL-15, AC-TEST-25) | The wait is a testing concern, so it stays out of the production API; the close guard fixes the race for every host | M8.1, 2026-11-27; owner; Q-24 (RFC 0002) |
| D-119 | **The by-value proxy is on by default in Spring TestContext contexts; `topolith.test.parity=warn` is P-2's release valve.** With `topolith-spring-test` present, every internal module's contracts are wrapped. `topolith.test.parity` is `fail` (default) or `warn`, read from the test context's `Environment`; there is no `off`. A `warn` run returns the provider's own result or exception and logs each violation once as TL0045, naming the module, method and JSON path, never the value. `@TopolithByValue` narrows the module ids; an `ApplicationContextRunner` context keeps `byValue()` opt-in. A missing JSON HTTP message converter fails context load only when an internal module provides a contract the proxy would wrap. Supersedes D-79's opt-in (R-TEST-04, AC-TEST-26 through AC-TEST-28) | 0.1.0 has no users to ratchet, starting strict stays compatible (D-27), and `warn` is exactly the valve P-2 promises | M8.1, 2026-11-27; owner; Q-26 (RFC 0002) |
| D-120 | **A loopback module's cross-module events go through the externalization adapter, as an external module's do; without an adapter startup fails with the external case's code.** Decision only: mode-aware events land in 0.3 (RFC 0001 §7), which builds this (R-MODE-06) | Loopback exercises the remote path in one process for every crossing; in-process delivery would hide the serialization and ordering gaps loopback exists to show | M8.1, 2026-11-27; owner; Q-27 (RFC 0002) |
| D-121 | **Relay also sends a login session's access token; a module route warns once when a relayed bearer token was not authenticated.** With `spring-security-oauth2-client` present, for an `OAuth2AuthenticationToken` relay resolves the session's `OAuth2AuthorizedClient` through a manager Topolith builds from the host's `ClientRegistrationRepository` and `OAuth2AuthorizedClientRepository` (never a host manager bean, so no winner is picked), refreshing as Spring does, and sends its access token; nothing resolved, or no current servlet request, sends nothing. As for `client-credentials` it needs exactly one of each repository bean: none turns session relay off, several fail startup with TL0047 when a module relays. A request to a module route that carries `Authorization: Bearer` and reaches the handler unauthenticated or anonymous logs TL0046 once per module. Amends D-93 and D-95 (R-EXT-13, AC-EXT-75, AC-EXT-76) | Session-based UIs are the common caller; the warning reports what happened, never a guess about the security chain, which answers D-95's objection to a startup heuristic | M8.1, 2026-11-27; owner; Q-28 (RFC 0002) |
| D-122 | **A host `ContextCarrier` bean carries one more string value across a call.** `ContextCarrier` (public, `@Incubating`, `io.github.rey5137.topolith.spring.boot`, host beans only, as `TenantContext`): `String name()`, `String current()`, `Scope open(String value)`. Each value crosses as `Topolith-Context-<name>` under D-90's encoding and limits; an invalid or duplicate name, or more than 16 MDC keys and carriers together, fails with TL0034, and an oversize or control-character value logs TL0035 naming the carrier. W3C baggage with a tracer stays Micrometer Tracing's (`management.tracing.baggage.remote-fields`), which the docs show. Amends D-90 (R-EXT-12, AC-EXT-77, AC-EXT-78) | String-typed by construction and the same shape as `TenantContext`; `ThreadLocalAccessor` values have no string form, and baggage without a tracer would duplicate Micrometer Tracing | M8.1, 2026-11-27; owner; Q-29 (RFC 0002) |
| D-123 | **Topolith's `exception` key is the parity contract of a call's error; Micrometer's `error` tag is not.** `error` names the class the caller receives in its mode, so it differs for a declared exception's subclass outside the api packages and for an undeclared exception; Topolith neither replaces the host's meter handler (INV-4) nor records a substitute error (R-EXT-11, AC-EXT-79) | Only `exception` can match in every case | M8.1, 2026-11-27; owner; Q-30 (RFC 0002) |
| D-124 | **The circuit-breaker adapter stays internal; a second supported implementation adds a package-private adapter and a bean-name selection key.** When it ships, a host with several supported `CircuitBreakerFactory` beans selects one with `topolith.resilience.circuit-breaker.factory=<bean name>`; without the key it still fails with TL0041. No public SPI until two implementations show what it needs. Decision only (R-EXT-15) | An SPI now would have to expose ownership checks and D-105's outcome recording, which are still moving | M8.1, 2026-11-27; owner; Q-31 (RFC 0002) |
| D-125 | **Topolith registers routes for a listed contract's bean (from M8.6).** For a module that is internal and exposed, or loopback, each abstract method of a contract listed in its declaration's `provides` gets one route invoking the module's bean of that contract, selected by bean-definition ownership, never by type. The mapping comes from Spring's own `getMappingForMethod` on a private, non-bean `RequestMappingHandlerMapping` subclass copying the host mapping's configuration and the `/<id>` prefix, registered with `registerMapping` from a `SmartInitializingSingleton`; no functional route. A method without an `@HttpExchange`-family annotation is TL0026; a path clash is TL0005. A handler-to-module registry, consulted first wherever Topolith decides a handler's module, gives the route the prefix, tenant check, `Topolith-Module` header, problem translation and exactly one observation per call; a `BeanPostProcessor` on `RequestMappingHandlerAdapter` puts a delegating return-value handler first for those methods only, writing the body as `@ResponseBody` would; AOT hints cover the contract methods and binding types. Relaxes D-110's TL0044 for listed contracts on loopback or exposed modules (R-EXT-05, R-DESC-02, AC-EXT-80, AC-EXT-81) | Ownership by class package would make such a route a host route (no tenant check, no problem translation, no header: INV-6), a lambda or proxy carries no `@ResponseBody`, `registerMapping` skips the prefix and unexposure, and a proxy's bean type would double the observation; the design closes each on standard extension points (INV-8). `WebMvcRegistrations` would silently drop a host's own (INV-4) and a runtime CGLIB introduction breaks native images (INV-9) | M8.1, 2026-11-27; owner, architect design; Q-32 (RFC 0002) |
| D-126 | **Every method of every contract an exposed or loopback module provides must have a module route; TL0044 otherwise.** At startup, for each module that is internal and exposed, or loopback, each abstract method of each provided contract must be implemented by a handler method of a bean the module owns by definition, or (from M8.6) by a route D-125 registers. An inferred contract implemented only by a non-handler `@Service` fails with TL0044 naming the module, contract, method and the module `@RestController` fix (R-EXT-05, AC-EXT-82) | Without the check the caller finds the missing route only at call time, as D-117's `status 404`; INV-4 wants the provider to fail at startup | M8.1, 2026-11-27; owner, architect review; Q-33 (RFC 0002) |
| D-127 | **The module term stays `api`; prose says "module API" wherever a gateway-facing surface is discussed.** `@TopolithModule.api()`, `api-packages`, `api-version`, the API descriptor and the API artifact keep their names: the API is the whole surface a module publishes (contract interfaces, value types, exceptions), and a contract is one provider interface in it. The gateway-facing routes proposed in Q-34 get a name without "API" in it | Renaming to `contracts` would make one word name both the whole and its parts, collides with Spring Cloud Contract's meaning, and would churn the descriptor format; `order-api` is the familiar artifact idiom. "Public API" already means the library's Java surface (D-109) | M8, 2026-11-27; owner |
| D-128 | **Startup reads a module declaration's listed `provides` from its `package-info.class`.** The descriptor does not tell listed contracts from inferred ones (D-110 keeps its format), so for each internal or loopback module Topolith parses, without loading, every `<package>/package-info.class` on the class path and takes the `provides` of the `@TopolithModule` (class retention) whose `id` is the module's, kept only where the descriptor's `provides` has them. A `none` or `external` module's declaration is never read (INV-2). Those contracts drive D-110's startup checks and let the R-EXT-07 boundary and the R-EXT-11 observation advise the listed contract's bean by its definition's owner when its class lies outside the module's package (R-DESC-02, AC-DESC-15, AC-DESC-16) | A descriptor key would change the format D-110 keeps; the class file is in the module's own artifact beside the descriptor, and Spring's annotation metadata skips class-retention annotations, so ASM reads it as the boundary check already reads module classes | M8.3, 2026-11-27; builder |
| D-129 | **A controller route covers a contract method when its handler is the module's by definition and by class; registered listed routes also count.** D-126's coverage counts a controller handler method whose bean definition the module owns (D-61) and whose class lies in the module's package, or a listed-contract route in the M8.6 handler registry (D-125); a module `@Bean` returning a controller class from outside its package is therefore uncovered, TL0044. The check runs once every singleton exists, before the server starts, over the handler methods of every `RequestMappingHandlerMapping`, and matches the handler method that implements the contract method in the handler's class (R-EXT-05, AC-EXT-82) | Counting the definition alone would pass a route served at the host's path, which the caller still finds only as a `status 404`, the failure D-126 exists to move to startup | M8.3, 2026-11-27; builder |
| D-130 | **The runner D-112 adds on a module's own DataSource takes none of the host's tool settings.** It gets the module's location or master file and its own tracking tables (Flyway baselined at version 0 as on the host's), the same ordering and the same limits, but no schemas, placeholders, contexts or connection settings from the host's Flyway, Liquibase or `spring.flyway.*`/`spring.liquibase.*`, which describe the host's database. A module that needs settings declares its own runner (P-2). A runner of the module's own, on its DataSource or the host's, means Topolith adds none, and one of the other tool than its files keeps TL0020 (R-MODE-04, AC-MODE-56, AC-MODE-57) | Copying the host's schema or placeholders onto another database would migrate the wrong schema silently (INV-4) | M8.3, 2026-11-27; builder |
| D-131 | **A session-relay refresh that fails sends nothing; the call proceeds unauthenticated**, as D-121's nothing-resolved case (R-EXT-13, AC-EXT-75) | A failed refresh resolves no access token | M8.8, 2026-11-28; orchestrator, owner may revisit |

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
This amends D-12: retry and circuit-breaker settings are not rebuilt on refresh until M7 (Q-23). Amended by D-102,
D-104 and D-105: R-EXT-15 configures the per-group breaker, R-EXT-14 and R-EXT-15 share the D-51 unwrap in one
outcome classifier, and R-EXT-08 rebuilds an external module's retry and circuit-breaker settings on refresh.

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

### D-56 — Descriptor-driven architecture rules (M3.1)

`TopolithArchitecture` (`topolith-spring-test`, `@Incubating`) has `verify()` (descriptors from the context class
loader), `verify(ClassLoader)`, `verify(modules, apis, JavaClasses)` and `rules(modules, apis)`, which returns the six
`ArchRule`s in `TopolithArchitecture.Rule` order (non-API access, undeclared dependency, `requires` cycles, descriptor
consistency, Topolith dependency, contract types); `rule(Rule, modules, apis)` finds one by constant (D-64). Every rule fails by default (D-4), so `verify()` also fails when it finds no module
descriptor. `verify(ClassLoader)` finds the descriptors and the classes through that loader, and test classes
(`ImportOption.DoNotIncludeTests`) are not checked.

- **Owner.** A class is owned by the module whose descriptor lists its package as an api package (exact match, also
  outside that module's `package`); otherwise by the one module whose `package` contains it; otherwise by none. Nested module packages, or another
  module's api package under a module's package, are a TL0017 failure of the consistency rule (the check startup
  uses, shared through `Descriptors.nesting`), never resolved by longest match: such a class has no owner.
  Classes owned by none, and the `contract` and `annotation` packages (D-54), are never checked as targets.
- **Access.** A dependency from module A to module B's class outside B's api packages fails; to B's api package it
  fails unless B is in A's `requires` or `optional`. A module's own classes, a controller implementing its own contract
  included (D-53), are never flagged.
- **Cycles.** The `requires` cycles of core's `ModuleGraph` (TL0008) fail the check; `optional` edges never do. Only
  TL0008 is reported under this rule.
- **Consistency.** Duplicate module ids (TL0006) and an api package listed by more than one descriptor id fail under
  their own rule: no owner is picked silently (INV-4).
- **Contract types.** The contracts are the public non-annotation interfaces of a module's api packages, narrowed to the
  descriptor's `provides` (binary names, `Outer$Inner` for a nested contract) when it lists any. Inherited
  methods and the generic arguments of the super-interfaces are checked too. A contract method may not use, in its
  return type, a parameter or a generic argument at any depth, a type that is or
  extends `org.springframework.data.domain.Slice` (so `Page`),
  `java.util.stream.BaseStream` (so `Stream`), `java.io.InputStream`, `org.hibernate.proxy.HibernateProxy`,
  `org.hibernate.collection.spi.PersistentCollection` or `java.lang.reflect.Proxy`, or one annotated
  `jakarta.persistence.Entity`. All are matched by name, so no Spring Data, JPA or Hibernate dependency is needed.

### D-57 — Frozen verify and baseline generation API (M3.2)

`TopolithArchitecture` (`@Incubating`) adds `frozenRules(modules, apis)` (each of the six D-56 rules except descriptor consistency (D-64) wrapped
in ArchUnit's `FreezingArchRule`, store entry keyed by the rule's fixed description) and `verifyFrozen()`, `verifyFrozen(ClassLoader)`
and `verifyFrozen(modules, apis, JavaClasses)`. The store is ArchUnit's, configured in `archunit.properties`
(`freeze.store.default.path`); `allowStoreCreation` stays false and `allowStoreUpdate` true, so a new violation fails
and a fixed one is dropped.

- **No store** (`stored.rules` absent): `verifyFrozen` freezes nothing and behaves exactly as `verify`: clean classes
  pass, a violation fails with the normal `AssertionError`. Nothing is created.
- **Store without a Topolith rule** (an adopter's own frozen rules only, or a rule text changed): fails naming the
  rule and `freeze.store.default.allowStoreCreation=true`; nothing is recorded silently.
- **Opt-in:** `allowStoreCreation=true` for one run records the first baseline (and adds missing rules).
  `freeze.refreeze=true` only re-records an existing store; it does not create one.
- The cycle rule reports from `finish` and freezes by message text; the consistency rule is never frozen (D-64).
- A custom `freeze.store` class is used by `verifyFrozen` as configured, without the missing-rule check.

`TopolithBaseline.generate(modules, apis, outputDir)` (and `generate(outputDir)` for the descriptors on the context
class loader) is called from an adopter test or `main`; there is no Maven plugin. It reads the store entry of the
non-API access rule only, whose messages start `<referencing class> -> <referenced class>: `, and writes one sorted,
de-duplicated `META-INF/topolith/baseline/<id>.txt` per referencing module (owner of the referencing class); a stale
file of a listed module with no violation left is deleted after the new files are written. Array-typed
dependencies are skipped (the component dependency is reported separately). `generate` rejects a custom
`freeze.store` class with an `IllegalStateException`, and `generate(outputDir)` fails like `verify` when no module
descriptor is found. The format reader and writer are the JDK-only
`io.github.rey5137.topolith.core.BoundaryBaseline`, in `topolith-core` so `topolith-spring-boot` (M3.3) can read the
resource without depending on ArchUnit.

### D-58 — Malformed packaged baselines (M3.3)

A malformed packaged boundary baseline fails startup with TL0030 naming the resource and bad line. Regenerate it
with `TopolithBaseline.generate`. The baseline is read only from the descriptor's root (D-63).

### D-59 — Topolith dependencies in module code (M3.3)

ArchUnit rejects dependencies from module or API code to `io.github.rey5137.topolith..` except its `annotation..` and `contract..` packages (D-43, D-54).

### D-60 — Host scans and auto-configuration back-off (M3.3)

A host component, servlet-component or configuration-properties scan at or above an internal module package fails TL0018, except for the standalone module's own package and scans that exclude the module's components. An internal module default-candidate bean that makes an auto-configuration's `@ConditionalOnMissingBean` back off fails TL0031 naming the module, bean and auto-configuration. Declare the bean `@Bean(defaultCandidate = false)` and inject it with a qualifier, or move it to the host. Persistence beans covered by TL0019 stay TL0019.

### D-61 — Other module registration resources (M3.3)

A bean definition is owned first by its declaring or factory-method class; API package classes are shared and do not
confer module ownership. Otherwise, its resource belongs to a
module when its path lies under that module’s package directory, or when it is inside a JAR containing only that
module’s descriptor. A directory classpath root alone does not confer resource ownership.

Boot 3 and later ignore the `META-INF/spring.factories` EnableAutoConfiguration key, so it is not checked. When actuator management auto-configuration is present, startup reads `META-INF/spring/*ManagementContextConfiguration.imports` resources and fails TL0007 naming the resource if an entry belongs to a non-internal module. The resource is checked directly because the management child context is never seen by the main context's bean-factory post-processor. The guard for excluding Topolith auto-configuration while a module is internal is D-111 (Q-13).

### D-62 — Test modes and mock servers (M3.4)

`topolith-spring-test` offers repeatable `@TopolithModuleMode` annotations for Spring Boot tests and an explicit
`TopolithTestModes` builder for an `ApplicationContextRunner` or `DynamicPropertyRegistry`. Unnamed modules remain
unset. An external annotation creates a context-owned `TopolithMockServer` and `@TopolithMock` injects it into a test
field. The context cache key includes the mode declarations. The builder accepts a server or a base URI; it only sets
properties. Each server listens on an ephemeral loopback port, serves stubs under the fixed `/<id>` prefix, records
requests, and can reset or close. Its problem responses use the provider's contract-exception format. Unstubbed paths
return 404 without a Topolith problem. All new public types are incubating.

**M3 gate amendment.**
- *External selection and a host service id (A8).* The annotation and the builder's `external(...)` also set
  `topolith.modules.<id>.remote.service-id` to the empty string. Boot treats a blank value as unset (TL0003,
  `ExternalClientProperties`), so a discovery host does not fail TL0003 and the mock URL wins. `internal` and `none`
  leave the host's service id untouched. The key strings are built in one package-private function shared by the
  annotation and the builder, and the builder's mode methods drop an earlier selection of the same module.
- *Precedence (A9).* The annotation's property source stays first, so it overrides application config (INV-3: the host
  states every mode). It overrides application config only. After every context customizer has run, a
  `BeanFactoryPostProcessor` inspects the test-level property sources: `Inlined Test Properties`
  (`@SpringBootTest(properties)`, `@TestPropertySource(properties)`) and `Dynamic Test Properties`
  (`@DynamicPropertySource`, `TopolithTestModes.applyTo(registry)`), found by name, and each `@TestPropertySource` file,
  read again from the merged descriptors with placeholders resolved against the context's environment and the declared
  `factory`, so a placeholder location or a custom factory is covered whatever name the source gets. A
  `DynamicPropertyRegistrar` bean registers only while singletons are created, so the same check runs again on
  `ContextRefreshedEvent` (the refresh then fails). If one sets a
  key the annotation also sets (`mode`; for `external` also `remote.url` and `remote.service-id`) to a different value
  (compared trimmed, case-insensitive), it throws `IllegalStateException` naming the module, the key, both values and
  the source. For `external` the annotation's URL is a fresh port, so any test-level `remote.url` conflicts. With
  `TopolithTestModes.applyTo(registry)` alongside the annotation, an equal `mode` passes and anything else fails.
  Annotations on a superclass or enclosing class take part in the same check.
- *Mock problems (A10).* `problem(method, path, type, message)` derives the status as `ModuleExceptionResolver` does
  (merged `@ResponseStatus`, else 500; a non-error status becomes 500). The explicit-status overload throws
  `IllegalArgumentException` outside 400-599, and, with spring-web present, when the type's merged `@ResponseStatus`
  derives another status. Both overloads reject `ModuleUnavailableException` with `IllegalArgumentException` pointing
  to `unavailable(...)`, which is the only way to stub the provider's 503. `unavailable(method, path, downstreamModuleId)` writes 503, the
  downstream module id and the provider's fixed detail. The provider's logic is package-private in
  `topolith-spring-boot`, so it is copied, not widened (a parity test guards it); `spring-web` is an optional
  dependency and without it the derived status is 500. The mock writes the exception class it is given, while the
  provider writes the declared contract type (`ContractExceptions.declared`); a test passes the declared type. Left
  open at the gate: mapping to the declared type.
- *Reset between tests (A11).* Tests with the same mode declarations share one cached context and so one mock server.
  `TopolithMockTestExecutionListener` calls `reset()` on every context-owned `TopolithMockServer` (stubs and recorded
  requests) both before and after each test method, as Spring Boot's `MockReset` does for mock beans. The reset before
  the method clears what the context recorded while starting. The api-version request of the readiness check is made
  from a daemon thread (`VersionReadiness`) and can land after any reset, so the mock serves but never records it
  (D-65); `requests()` holds only the test's own calls. The reset runs before the user's `@BeforeEach`,
  so `@BeforeEach` stubs survive; a stub made in `@BeforeAll` is gone before the first method. The mock serves no
  built-in endpoint: `reset()` removes only stubs and requests, and an unstubbed path (including
  `/<id>/.topolith/api-version`) answers 404 before and after. The listener finds only the servers the customizer
  registered, by name and without eager init, so it creates no lazy bean; a `TopolithMockServer` bean a test declares
  itself is not reset.
- *Public surface (A12).* `TopolithContextCustomizerFactory` and `TopolithMockTestExecutionListener` are
  package-private (Spring loads them reflectively through `spring.factories`; a test covers it). The module id
  attribute is `id` on both `@TopolithModuleMode` and `@TopolithMock` (the former has two attributes, so `value` was not
  available there): `@TopolithMock("x")` becomes `@TopolithMock(id = "x")`, an accepted pre-0.1 break. A
  `TopolithTestModes` module named without a mode makes `applyTo` throw `IllegalStateException` naming it.
- *Problem title (parity).* The mock adds a `title` member, the status's HTTP reason phrase, as the provider's problem
  converter does (omitted when the status has no standard phrase or spring-web is absent for a non-500 status).

### D-63 — Baseline and startup-scan scope (M3 gate)

A module's packaged baseline is the `META-INF/topolith/baseline/<id>.txt` in the class-path root that holds
`META-INF/topolith/modules/<id>.properties`. `TopolithBaseline.generate` writes `<id>.txt` only into an output
directory holding that module's descriptor, skips modules whose descriptor is elsewhere, and fails with
`IllegalArgumentException` when the directory holds no module descriptor at all. The startup check (R-ARCH-05) reads
only that copy, fails TL0030 on a copy in another root, and scans only that root's classes for the module, so test
roots and other JARs neither add violations nor waive them. An internal module with no descriptor root fails with
`IllegalStateException`. Trade-off: a module whose classes span several main roots (for example Gradle
`classes/java/main` and `classes/kotlin/main`) has only its descriptor root scanned at startup; ArchUnit still covers
every root in the build. The startup reference set equals ArchUnit's dependency set for the non-API rule, proven by the end-to-end freeze ->
generate -> start test; neither checks called-member signature types, an accessed field's type, casts, array creation or
inlined constants, and `BoundaryParityTest` keeps them in step.
Amended at M3 gate step 2: the startup check reads `<id>.txt` from the module's descriptor root, or from a directory
root (never a JAR or nested JAR) that holds no module descriptor at all, so a committed `src/main/resources` copy works
on Gradle, where resources and classes are separate directories. This is a development-run allowance: a packaged app
(JAR, fat JAR) accepts only the descriptor root, so no JAR can widen another module's baseline; a test-resources
directory could also carry a baseline, but only in test runs. A copy in another module's descriptor root or in a
non-descriptor JAR fails TL0030, and two accepted copies fail TL0030 naming both; startup never merges them. The class
scan stays descriptor-root only. `TopolithBaseline.generate(outputDir, String... moduleIds)` writes the named modules
into any directory, deletes only their stale files and throws on an unknown id; the descriptor-gated
`generate(outputDir)` is kept unchanged.
Amends D-25, D-57, D-58, R-ARCH-03, R-ARCH-05 and AC-ARCH-15.
Alternatives: merge every copy (lets any JAR widen another module's baseline, P-4); mirror ArchUnit's test-location
patterns (does not solve the baseline owner and duplicates a path heuristic).

### D-64 — Stable architecture rule names (M3 gate)

`TopolithArchitecture.Rule` (`@Incubating` enum) lists the rules: `NON_API_ACCESS`, `UNDECLARED_DEPENDENCY`,
`REQUIRES_CYCLE`, `DESCRIPTOR_CONSISTENCY`, `TOPOLITH_DEPENDENCY`, `CONTRACT_TYPES`. `rule(Rule, modules, apis)` finds
one; `rules(modules, apis)` keeps returning all six in enum order, and `TopolithBaseline` looks up `NON_API_ACCESS`.
Each rule's description is a fixed key that carries no spec id: `topolith: non-API access`,
`topolith: undeclared dependency`, `topolith: requires cycle`, `topolith: descriptor consistency`,
`topolith: Topolith dependency`, `topolith: contract types`. ArchUnit's freeze store is keyed by the description, so the
keys are a compatibility surface: renaming one is a breaking change listed in the release notes, and moving to these
keys invalidates stores recorded under the earlier prose descriptions (re-record with `allowStoreCreation=true`).
Amends D-56 and D-57.
- **Consistency is never frozen.** `frozenRules` and `verifyFrozen` run `DESCRIPTOR_CONSISTENCY` unfrozen, and a store
  need not hold an entry for it: startup refuses those topologies (duplicate ids, contested api packages, TL0017
  nesting), so they are never legacy debt, and freezing them would let nested packages pass with every other rule
  skipping the ambiguous classes.
- **Nesting (TL0017).** The consistency rule reports TL0017 through `Descriptors.nesting(modules, apis)` (public in
  core, covered by the class-level `@Incubating`), the check `Topology` runs at startup. `Ownership` returns no owner
  for a package inside more than one module package; it never picks the longest.
- **`importPackages`** is package-private (only `verify(ClassLoader)` needs it); adopters with their own `JavaClasses`
  use `verify(modules, apis, JavaClasses)`.
Alternative: keep `List<ArchRule>` by position (already shifted once in M3.3; index-coupled tooling).

### D-65 — The version check is invisible to `requests()` (M3 gate)

The api-version request of R-VAL-04 comes from a daemon thread at `ApplicationReadyEvent`, and again after a client
refresh (D-50). It can therefore arrive after any reset (A11).

`TopolithMockServer` answers `GET /<id>/.topolith/api-version` from its stubs (404 when unstubbed, as before) but does
not add it to `requests()`. Every other request is recorded, including other methods and other `/.topolith/...` paths.
The path is copied from the boot module and guarded by a test.

Rejected:
- A public `@Incubating` readiness wait in `topolith-spring-boot`. It is a blocking primitive only tests need, it would
  miss D-50 re-checks, and it invites gating startup on a warning-only check (D-24, D-34).
- Holding Boot readiness until the check ends, which contradicts D-24 and D-34.
- Reflection from spring-test into the package-private `VersionReadiness`.
- Documentation only.

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
  Functional (`RouterFunction`) routes are not module handlers and are not governed. Amended by D-76: a loopback
  module's routes are always mapped, and only a module that is neither internal nor loopback cannot be exposed.
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
  TL0029 whether or not a `PlatformTransactionManager` exists (amended by D-89), as Spring's own `@Transactional`
  would; an interface-only proxy would hide the class's routes. A `final` non-handler (a lambda, a JDK proxy class)
  gets the interface proxy, and a CGLIB proxy that is already frozen keeps a class-based proxy. The managers are looked
  up on every call, so one created after startup is included.
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
Amended by D-74: the deferred work is scheduled for 0.1.0 in M7, and until M7 lands only timeouts are mandatory.
Superseded by D-100 through D-105 (R-DESC-05, R-EXT-14, R-EXT-15).

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

`TopolithBaseline.generate` writes `META-INF/topolith/baseline/<id>.txt` in the referencing module's descriptor root (D-63) from the frozen
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
only by a lambda or `@Bean` fails the build (Q-11; relaxed by D-110).

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
| Q-6 | Answered (D-16; superseded by D-101, D-102, D-105) | Which retry and circuit-breaker policies and defaults apply in 0.1? |
| Q-7 | Answered (D-17) | Does M3 require detecting internal cross-module access at startup? |
| Q-8 | Answered (D-16; superseded by D-100) | How is a contract call declared or determined to be idempotent for retry? |
| Q-9 | Answered (D-18) | How can Spring-based test helpers satisfy INV-7's Spring import restriction? |
| Q-10 | Answered (D-19) | Which slice owns the Maven plugin's hand-written descriptor verification goal? |
| Q-11 | Answered (D-110) | Should `@TopolithModule` gain an explicit `provides` attribute for consumer-implemented SPI interfaces and lambda-only contracts, which D-29 rejects? |
| Q-12 | Answered (D-33) | Should startup fail when a host's API JAR `provides` differs from the module descriptor (M1.5)? |
| Q-13 | Answered (D-111) | How can Topolith detect its own auto-configuration being excluded while a module is internal? Management imports and the ignored `spring.factories` key are covered by D-61. |
| Q-14 | Closed (D-60) | Should Topolith handle general bean replacement (a module's default-candidate `JdbcTemplate`, `ObjectMapper`) and a host scan that covers an internal module, which registers its beans twice (M3.3)? |
| Q-15 | Answered (D-32) | Should Liquibase be supported for module migrations (it has one master changelog with explicit includes)? |
| Q-16 | Answered (D-112) | Should Topolith run a Pattern A module's migrations on the module's own DataSource? |
| Q-17 | Answered (D-113) | Spring Data JDBC is registered per module in 0.1. Should Topolith register R2DBC, MongoDB and MyBatis per module? They stay at TL0024 in 0.1. |
| Q-18 | Answered (D-114) | A host's `mybatis.mapper-locations` (for example `classpath*:mapper/**/*.xml`) can load a `none` module's mappers (INV-2); should it be a check like TL0020's host-location check? |
| Q-19 | Answered (D-115) | A module with no contracts (listener-only, jobs) still has to declare an api package (`@TopolithModule.api()` is required and `api-packages` is non-empty); should that be relaxed (compatible later, D-27)? |
| Q-20 | Closed (D-59) | `Topology` is an injectable public bean, so business code could branch on a module's mode (INV-1, INV-6); should M3.3 add an ArchUnit rule forbidding module code from depending on `topolith-core` (only the `annotation` and `contract` packages allowed, D-43)? |
| Q-21 | Answered (D-116) | Should a contract support checked exceptions (TL0027 rejects them) or carry exception payload beyond the message (fields, cause chain) across HTTP (D-43)? |
| Q-22 | Answered (D-117) | Should a 404, 405 or 415, or a 401 or 403, without a Topolith problem become `ModuleUnavailableException` rather than an unspecified exception, and should a fixed JDK list (`IllegalArgumentException`, `IllegalStateException`, `UnsupportedOperationException`) count as declared (D-43)? |
| Q-23 | Answered (D-104) | Should a refresh rebuild a group's Spring Cloud CircuitBreaker and retry settings (D-12, D-51), or stay restart-only? |
| Q-24 | Answered (D-118) | The remote version check's TL0015/TL0023 can be logged after a test method starts, or after its cached context closes, so a test that asserts on those log lines in external mode races. Does a test need a supported wait (a spring-test call over a boot `@Incubating` method), or is a per-context log assertion enough? |
| Q-25 | Answered (D-79): no | Should the by-value proxy also fail a caller that changes its own copy of a result (D-79)? In internal mode without the proxy that change reaches the provider's object, which matters only when the provider kept it; failing it would also fail code that is correct in every mode. |
| Q-26 | Answered (D-119) | The by-value proxy is opt-in in 0.1.0 (D-79), while P-2 says parity is on unless explicitly relaxed to `warn`. Should parity follow a P-4-style ratchet (opt-in in 0.1, then default `warn`, then default `fail`), and how is P-2's `warn` release valve specified (key, scope, what a `warn` run logs)? |
| Q-27 | Answered (D-120) | Loopback checks contract-call parity only (R-MODE-06). Once mode-aware events land (RFC 0001 §7, 0.3), should a loopback module's cross-module events go through the adapter, or fail as "would be lost", as for an external module? |
| Q-28 | Answered (D-121) | Token relay carries only an `AbstractOAuth2TokenAuthenticationToken`'s bearer token (D-93, D-95). Should it also relay the access token of an OAuth2 login session (`OAuth2AuthenticationToken`, through the authorized client), and should a loopback host whose security cannot authenticate a relayed token get a startup warning rather than a documented gap? |
| Q-29 | Answered (D-122) | Context propagation covers listed MDC keys and one tenant (D-90, D-91). Should it carry more, for example values held by Micrometer context-propagation `ThreadLocalAccessor`s or W3C baggage without a tracer, and with what string mapping? |
| Q-30 | Answered (D-123) | Micrometer's meter handler adds an `error` tag from the thrown exception's class, which differs between modes for a declared exception's subclass outside the api packages and for an undeclared exception (D-87). Should Topolith make that tag match too (for example by recording the declared class), or is its own `exception` key the parity contract? |
| Q-31 | Answered (D-124) | In 0.1 `Resilience4JCircuitBreakerFactory` is the only supported `CircuitBreakerFactory` implementation, wired through a package-private adapter in `topolith-spring-cloud` (D-105). When a second implementation is added, should the adapter remain internal or become a public `@Incubating` SPI (and should a host with multiple factories be able to select one by bean name)? |
| Q-32 | Answered (D-125) | Should Topolith register routes for a contract listed in a module declaration's `provides` (a `@Bean`-provided contract), from its `@HttpExchange`, through Spring's mapping rules and registry-based handler ownership, rather than fail with TL0044 when the module is loopback or exposed (D-110)? |
| Q-33 | Answered (D-126) | An inferred contract implemented only by a non-handler `@Service` in an exposed or loopback module has no route, and nothing fails at startup; the caller sees D-117's `status 404` at call time. Should startup check that every provided contract method has a module route? |
| Q-34 | Open (0.2, RFC 0003) | How should a module serve gateway-facing routes for several gateways? Sketch: an audience (`@Audience("mobile")` on a module's controller class or package) whose routes a host maps only when it lists the audience (`topolith.audiences.<name>.path`) and the module runs in that process; unknown, unserved or overlapping audiences and paths fail startup; audience routes ignore `Topolith-*` headers and never return a Topolith problem. An `AudienceGuard` in a gateway module validates requests (method, path, headers, principal; no body) and decorates responses (headers always; a JSON body rewrite only when declared with media types and a size limit), run as a Spring Cloud Gateway Server MVC filter or a servlet filter wherever the gateway module is internal; reactive gateways host no Topolith module (TL0028). An audience artifact carries `AudienceAdvice` (envelope, error shape, mapping of `ModuleUnavailableException` and undeclared exceptions) applied at the source in every host serving the audience. Open: names, forced module id in the path, per-host module subsets, OpenAPI groups, gateway route generation, gateway-to-service trust (JWT pass-through preferred). Naming follows D-127 |
