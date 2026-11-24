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
| D-7 | **Inclusion-based activation:** the host scans only its own package; a registrar imports internal modules and an `AutoConfigurationImportFilter` drops non-internal modules' auto-configurations; migrations live under `db/migration/<id>` | Component-scan exclusion misses auto-configurations, `@Import`, entity scanning and Flyway's merged migration path, breaking INV-2 | RFC 0001 §12 row 3 |
| D-8 | **Modes are explicit in hosts** (INV-3); only a standalone `*-service` defaults its own module to `internal` | A transitive dependency on an implementation JAR must never activate a module silently | RFC 0001 §12 row 6 |
| D-9 | The descriptor is **generated** from `@TopolithModule` on `package-info.java` by an annotation processor; a hand-written one is verified against the bytecode in the build | Hand-written package lists drift on refactoring; `.properties` has no trailing comments | RFC 0001 §12 row 7 |
| D-10 | **Baseline:** Java 21, Spring Boot 4.2, Spring Framework 7; Spring Cloud 2026.0 only for discovery | Current GA lines at project start; HTTP service groups and their LoadBalancer configurer need Framework 7 / Boot 4 | — |
| D-11 | **A mode change needs a restart.** If an `EnvironmentChangeEvent` (Spring Cloud `/actuator/refresh`, a Config Server push) changes any `topolith.modules.<id>.mode`, Topolith logs a `TLnnnn` warning that names each changed module with its running and requested modes, and the actuator endpoint reports a restart as pending. The running topology does not change. | INV-9 fixes the topology at context refresh, because a mode decides which bean definitions, auto-configurations, entities and migrations exist, and `@RefreshScope` can only rebuild beans that already exist. Ignoring the change without a word would break INV-4. Switching to `internal` after a restart still needs the implementation JAR on the host's classpath (P-3). | INV-4, INV-9 |
| D-12 | **An `external` module's endpoint settings can change on refresh.** On an `EnvironmentChangeEvent` that changes `remote.url`, timeouts, or retry or circuit-breaker settings, Topolith rebuilds that module's HTTP service group client. Calls already in flight finish on the old client. Changing between `remote.url` and `remote.service-id` changes the module's wiring, so it is treated like a mode change and gets the D-11 warning instead. | These settings aren't topology, so INV-9 allows them to change. Endpoints already move at runtime through `remote.service-id` and discovery, and requiring a restart to repoint a URL or adjust a timeout would gain nothing. | Q-2 (answered) |

## Open questions

None open.
