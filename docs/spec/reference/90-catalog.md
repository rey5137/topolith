# 90 — Diagnostics, exceptions and glossary

This file defines 0.1 diagnostic meanings, the exception catalog and shared vocabulary.
Rules that raise a diagnostic refer to its stable code; meanings are never reused.

## Rules

### R-CAT-01 — Diagnostic catalog

Each diagnostic has the meaning and severity below; failures include an actionable fix. TL0012 is suppressed for a
violation in the frozen baseline. Implements INV-4, INV-9, INV-10, D-11, D-12, D-23, D-24 and D-25.

| Code | Meaning | Severity | Owning slice |
|---|---|---|---|
| TL0001 | A host has not declared a valid mode for a known module | failure | M1.3 |
| TL0002 | An internal or loopback module has no implementation JAR | failure | M1.3 |
| TL0003 | A module setting names an unknown module, contradicts the module's mode, or contradicts another setting. Uses: an external module has neither or both URL and service id; a Topolith client property conflicts with Boot's; a module that is neither internal nor loopback is exposed (D-44, D-76); `expose.enabled` is not a boolean; an `expose.*` key under `topolith.modules.<id>` names an unknown module; a host HTTP service group named like an external or loopback module holds an interface that is not that module's; a client customizer names a module that is neither external nor loopback (D-48); a `remote.http-interface` mapping is invalid (D-49); a loopback module sets a non-blank `remote.url` or `remote.service-id`, a user-set Boot `base-url` or `expose.enabled=false` (R-MODE-06), or a provider bean that is `@Primary` or whose class carries `@Priority` (D-84); a `loopback.*` key is set for a module that is not loopback, is unknown, or holds an invalid value (R-EXT-10); an `auth.*` key is set for a module that is neither external nor loopback, is unknown, or holds an invalid value, or `ModuleClients.authenticate` names such a module (R-EXT-13) | failure | M1.5 (AC-EXT-11) |
| TL0004 | An internal or loopback module's required module is unavailable | failure | M1.3 |
| TL0005 | More than one provider supplies the same contract | failure | M1.5 |
| TL0006 | Two module descriptors, or two API descriptors, declare the same id | failure | M1.1 |
| TL0007 | A non-internal module owns a registered bean, auto-configuration, entity or repository | failure | M3.3 |
| TL0008 | Required module dependencies contain a cycle | failure | M1.1 |
| TL0009 | Service-id discovery needs a missing Cloud integration (`spring-cloud-starter-loadbalancer`) | failure | M2.2 (AC-EXT-06) |
| TL0010 | A refreshed mode or client wiring change (a URL/service-id switch, a loopback module's client settings, an `auth.*` key) needs a restart | warning | M2.6 |
| TL0011 | Refreshed endpoint settings are invalid or the client rebuild failed | warning | M2.6 |
| TL0012 | An internal class references another module's non-API class | failure | M3.3 |
| TL0013 | A module or API descriptor is malformed | failure | M1.5 |
| TL0014 | Cloud context classes are present without Topolith refresh integration | warning | M2.6 |
| TL0015 | The remote API-version endpoint is unavailable or inaccessible | warning | M2.4 |
| TL0016 | A mode is declared for a module that is not on the classpath | failure | M1.3 |
| TL0017 | Module packages nest, or another module's API package lies under a module's package | failure | M1.3 |
| TL0018 | The host's auto-configuration or entity-scan packages overlap a module's package, or its component, servlet-component or configuration-properties scan covers an internal module, except the standalone module's own (D-60) | failure | M1.4 |
| TL0019 | An internal module's own DataSource, entity-manager, transaction-manager, Flyway, Liquibase or `AbstractJdbcConfiguration` bean is a default candidate and would replace the host's | failure | M1.4 |
| TL0020 | A module's migrations would not run exactly once: no runner or DataSource for its tool, both tools or several changelogs, a host migration location or changelog reaches them, or a tracking table name is too long | failure | M1.4 |
| TL0021 | An internal module's entity is managed by no entity-manager factory, or by several | failure | M1.4 |
| TL0022 | An internal module and its host API descriptor declare different contracts | failure | M1.5 |
| TL0023 | A host API descriptor's `api-version` major differs from its module's descriptor or remote provider | warning | M1.5 |
| TL0024 | A Spring Data repository or MyBatis mapper interface in an internal module's or the host's package has no registration. Fix: register it (use Spring Data JPA or JDBC, or `@Enable<Store>Repositories` or `@MapperScan` on the package), or mark an interface left unregistered on purpose `@NoRepositoryBean`. With multiple Spring Data stores, annotate the aggregate for its store (for JDBC, `@Table`) or extend a store-specific repository interface so strict mode can assign it | failure | M1 gate (AC-MODE-30) |
| TL0025 | `topolith.standalone` is set while another module with a descriptor is `internal` | failure | M1 gate (AC-MODE-31) |
| TL0026 | An external or loopback module has no usable client: spring-web or Boot's HTTP service-client auto-configuration (`spring-boot-starter-restclient`) is missing, a provided contract cannot be loaded, or it carries no `@HttpExchange`; or a loopback module has no plain-HTTP servlet web server listening on a port to call (a non-web application, a context that is not a `WebServerApplicationContext` such as a `@SpringBootTest` `MOCK` environment, `server.port=-1`, or a server that uses SSL by Boot's resolved setting) | failure | M2.1 (AC-EXT-26..28) |
| TL0027 | A contract's exceptions cannot cross HTTP: a contract method of an internal, loopback or external module declares a checked exception, or a concrete unchecked exception class in its api packages is not public, has neither a public `(String)` nor a public no-arg constructor, or carries a `@ResponseStatus` (direct, composed or inherited) that is not 4xx or 5xx. Raised in every topology, a monolith too | failure | M2.3 (AC-EXT-30) |
| TL0028 | A module is exposed (`topolith.modules.<id>.expose.enabled=true`) or `loopback` in a reactive (WebFlux) application, which has no exposure support (D-43, D-44, D-76) | failure | M2.4 (AC-EXT-11) |
| TL0029 | A provider bean of an internal module that implements a provided contract is a `final` handler class (a `@Controller`, `@RestController` or `@RequestMapping` type), so the class proxy that carries the contract transaction boundary (D-47) and the call observation (D-89) cannot be made; it fails whether or not a transaction manager or an `ObservationRegistry` bean exists, and turning observation off does not avoid it; the fix is a non-final class; a `final` provider that is not a handler takes an interface proxy | failure | M2.4 (AC-EXT-16) |
| TL0030 | A packaged boundary baseline is malformed; regenerate it with `TopolithBaseline.generate`; a copy outside the module descriptor's root also fails (D-58, D-63) | failure | M3.3 (AC-ARCH-16) |
| TL0031 | An internal module default-candidate bean makes an auto-configuration back off (D-60); make it a non-default candidate and use a qualifier, or move it to the host | failure | M3.3 (AC-ARCH-28) |
| TL0032 | A test class is in a loopback module's package or a subpackage, where D-81's caller rule would treat it as inside the module (D-85); move the test class out of the module's package | failure | M5 gate (AC-TEST-20) |
| TL0033 | More than one `ModuleCallObservationConvention` bean exists, so the call observation's convention is ambiguous; keep one (D-89) | failure | M6.2 (AC-EXT-47) |
| TL0034 | Context propagation is misconfigured: a `topolith.context.mdc-keys` entry is not letters, digits, `.`, `_` and `-` starting with a letter or digit, two entries are equal ignoring case, more than 16 are listed, a key is listed without SLF4J, or more than one `TenantContext` bean exists (D-90, D-91) | failure | M6.3 (AC-EXT-52) |
| TL0035 | An outgoing MDC value is over 1024 characters once percent-encoded or has an ISO control character, so it is not sent; the log names the key, never the value (D-90) | warning | M6.3 (AC-EXT-51) |
| TL0036 | A module has more than one outgoing-auth hook: two `ModuleClients.authenticate` beans, or one and an explicit `topolith.modules.<id>.auth.type`, at startup, or a customizer sets a default `Authorization` header on a module that has a hook; or, on a call, a hook changes an `Authorization` header an interceptor already set, which fails the call with a `TopolithException` (D-92) | failure | M6.4 (AC-EXT-55) |
| TL0037 | `auth.type=client-credentials` cannot be satisfied: Spring Security's OAuth2 client classes are missing, `auth.registration-id` is missing or names no registration in the `ClientRegistrationRepository`, or there is not exactly one `ClientRegistrationRepository` and one `OAuth2AuthorizedClientService` bean to build the hook's manager from, or more than one client-credentials `OAuth2AccessTokenResponseClient` bean (D-94, D-96) | failure | M6.4 (AC-EXT-56) |
| TL0038 | An outgoing tenant is over 1024 characters once percent-encoded or has an ISO control character, so the call fails before it is sent with a `TopolithException` (D-91) | failure | M6.3 (AC-EXT-51) |
| TL0039 | A request carrying `Topolith-Tenant` reached a module route of a host with no `TenantContext` bean, so the provider answers 500 without running it and the caller receives the unspecified exception (D-98) | failure | M6.3 (AC-EXT-51) |

The owning slice names the slice that owns the code's acceptance criterion, not the first slice that raises it. M1.3
raises TL0007 early, only when a host's component scan covers a non-internal module's package (AC-MODE-24); M1.4 also
raises it for a non-internal module's entity or repository that a host registers (AC-MODE-11); M3.3 generalises the
check, including servlet-component and configuration-properties scans and artifact registration resources. M1.4 owns
TL0018 for auto-configuration and entity scans; M3.3 owns its component, servlet-component and configuration-properties
scan cases (D-60). A host migration location or changelog that reaches a non-internal module's scripts is TL0020 in every mode (AC-MODE-27). A known module is one with a module or API descriptor. TL0002 also covers an `internal` or
`loopback` mode for an id with no module descriptor. M5.3 raises TL0002, TL0003, TL0004, TL0010, TL0026 and
TL0028 for a loopback module (AC-MODE-39, AC-MODE-42, AC-MODE-44, AC-MODE-45, AC-MODE-47, AC-MODE-51) and M5.4 raises
TL0003 for `loopback.*` keys (AC-EXT-39). M6.2 raises TL0029 with no transaction manager and no
`ObservationRegistry` bean too (AC-EXT-46), and M6.4 raises TL0003 for `auth.*` keys and TL0010 for their refresh
(AC-EXT-56).

Diagnostic criteria are AC-MODE-02, AC-MODE-05, AC-MODE-08, AC-MODE-21 through AC-MODE-24, AC-MODE-11, AC-MODE-25 through AC-MODE-28, AC-MODE-30, AC-MODE-31, AC-DESC-08,
AC-DESC-10, AC-VAL-01 through AC-VAL-05, AC-VAL-14, AC-ARCH-09, AC-ARCH-11, AC-EXT-06, AC-MODE-13, AC-MODE-18, AC-EXT-22 through
AC-EXT-24, AC-EXT-30, AC-VAL-10 through AC-VAL-12, AC-MODE-39, AC-MODE-42, AC-MODE-44, AC-MODE-45,
AC-MODE-47, AC-MODE-51, AC-EXT-39, AC-EXT-46, AC-EXT-47, AC-EXT-51, AC-EXT-52, AC-EXT-55 and AC-EXT-56.

### R-CAT-02 — Exception catalog

`io.github.rey5137.topolith.contract.ModuleUnavailableException` (in `topolith-core`, final, unchecked) means the
module named by `moduleId()` could not be reached; it is a declared exception of every contract (D-43). Declared
exceptions preserve their type across HTTP, per R-EXT-04. `TopolithException` carries `TLnnnn`-coded topology and configuration failures (D-28);
it is outside INV-6's contract exceptions, and `ModuleUnavailableException` does not extend it. Implements INV-6.

Exception criteria are AC-EXT-09, AC-EXT-10, AC-EXT-29 and AC-EXT-32.

### R-CAT-03 — Vocabulary

Use these terms in descriptors, configuration and diagnostics. Implements P-1 and P-3.

| Term | Meaning |
|---|---|
| Module | A business unit identified by metadata and an id. |
| Contract | An API type a module provides for calls by another module. |
| Host | A Spring Boot application that chooses module modes. |
| Internal | Mode that runs a module's implementation in the host. |
| External | Mode that calls a module through a remote client. |
| None | Mode that does not activate a module or its client. |
| Loopback | Mode that runs a module's implementation in the host and calls its contracts over HTTP on the host's own server. |
| Active for `requires` | An internal or loopback module; external dependencies are satisfied by the remote service. |
| Required dependency | A module that an active module needs through `requires`. |
| Optional dependency | A declared dependency whose absence does not make the graph invalid. |
| Restart pending | Configuration requests wiring or a mode different from the running topology. |
| Call observation | The one `topolith.module.call` Micrometer Observation of a contract call, on the caller's side. |
| Caller module | The module whose package holds the code making a contract call; `_host` for host code. |
| Propagated context | The listed MDC values and the tenant a loopback or external call carries to its provider. |
| Outgoing-auth hook | The one interceptor that sets an external or loopback module's call credentials. |
| Token relay | The default hook: sends the calling thread's bearer token as the call's `Authorization`. |

- AC-CAT-01 (M4.3): The configuration reference uses these mode and restart-pending meanings.
- AC-CAT-02 (M8.4): The configuration reference uses the loopback meaning.
