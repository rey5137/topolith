# Diagnostic codes

Every topology or configuration problem carries a stable `TLnnnn` code and a fix-it message. A code's meaning is never
reused. A **failure** stops startup (or, for the boundary codes, fails the check); a **warning** is logged and the host
keeps running.

## Startup topology

| Code | Meaning | Severity | Fix |
|---|---|---|---|
| `TL0001` | A host has not declared a valid mode for a known module. | failure | Set `topolith.modules.<id>.mode` to `internal`, `external` or `none`. |
| `TL0002` | An internal module has no implementation JAR (also an `internal` mode for an id with no module descriptor). | failure | Add the `<id>-module` JAR, or choose `external` or `none`. |
| `TL0003` | A module setting names an unknown module, contradicts the module's mode, or contradicts another setting: an external module with neither or both `remote.url` and `remote.service-id`; a Topolith client property that conflicts with Boot's; a module that is not internal being exposed; `expose.enabled` not a boolean; an invalid `remote.http-interface` mapping; a client customizer for a module that is not external; an `expose.*` key under an unknown module; a host HTTP service group named like an external module that holds another module's interface. | failure | Fix the named setting so exactly one endpoint is set and the setting matches the mode. |
| `TL0004` | An internal module's required module is unavailable. | failure | Make the required module `internal` or `external`, or remove the requirement. |
| `TL0005` | More than one provider supplies the same contract. | failure | Keep one provider of the contract. |
| `TL0006` | Two module descriptors, or two API descriptors, declare the same id. | failure | Give each module a unique id. |
| `TL0008` | Required module dependencies contain a cycle. | failure | Break the cycle in `requires`; move one edge to `optional` if it is not needed to start. |
| `TL0013` | A module or API descriptor is malformed (also a blank `-Atopolith.apiVersion`). | failure | Regenerate the descriptor with the processor, or correct the hand-written file. |
| `TL0016` | A mode is declared for a module that is not on the classpath. | failure | Add the module's API or module JAR, or remove the property. |
| `TL0017` | Module packages nest, or another module's API package lies under a module's package. | failure | Move the packages so none contains another. |
| `TL0022` | An internal module and its host API descriptor declare different contracts. | failure | Align the API JAR and the module version. |
| `TL0025` | `topolith.standalone` is set while another module with a descriptor is `internal`. | failure | Set the other modules to `external` or `none`, or drop `topolith.standalone`. |

## Ownership and scanning

| Code | Meaning | Severity | Fix |
|---|---|---|---|
| `TL0007` | A non-internal module owns a registered bean, auto-configuration, entity or repository. | failure | Narrow the host's scan so it does not cover the module, or set the module `internal`. |
| `TL0018` | The host's auto-configuration or entity-scan packages overlap a module's package, or a component, servlet-component or configuration-properties scan covers an internal module. | failure | Keep host scan packages away from module packages (the standalone module's own package is exempt). |
| `TL0019` | An internal module's own `DataSource`, entity-manager, transaction-manager, Flyway, Liquibase or `AbstractJdbcConfiguration` bean is a default candidate and would replace the host's. | failure | Declare it `@Bean(defaultCandidate = false)` with a `@Qualifier`. |
| `TL0020` | A module's migrations would not run exactly once: no runner or `DataSource` for its tool, both tools or several changelogs, a host location or changelog reaches them, or a tracking table name is too long. | failure | Use one tool per module under `db/modules/<id>` and keep host migration locations away from it. |
| `TL0021` | An internal module's entity is managed by no entity-manager factory, or by several. | failure | Make exactly one entity-manager factory manage it. |
| `TL0024` | A Spring Data repository or MyBatis mapper interface in an internal module's or the host's package has no registration. | failure | Register it (`@Enable<Store>Repositories` or `@MapperScan`), or mark it `@NoRepositoryBean`. With several stores, annotate the aggregate for its store or extend a store-specific interface. |
| `TL0031` | An internal module's default-candidate bean makes an auto-configuration back off. | failure | Make it a non-default candidate with a qualifier, or move it to the host. |
| `TL0032` | A test class is in a loopback module's package or a subpackage. | failure | Move the test class out of the module's package. |

## External mode and contracts

| Code | Meaning | Severity | Fix |
|---|---|---|---|
| `TL0009` | Service-id discovery needs a missing Cloud integration. | failure | Add `spring-cloud-starter-loadbalancer`. |
| `TL0026` | An external module has no usable client: no spring-web or `spring-boot-starter-restclient`, a provided contract cannot be loaded, or it carries no `@HttpExchange`. | failure | Add `spring-boot-starter-restclient`; annotate the contract or map a separate HTTP interface. |
| `TL0027` | A contract's exceptions cannot cross HTTP: a contract method declares a checked exception, or an unchecked exception class in its API packages is not public, lacks a public `(String)` or no-arg constructor, or has a `@ResponseStatus` that is not 4xx or 5xx. Raised in every topology. | failure | Declare only unchecked exceptions with a public constructor and a 4xx or 5xx status. |
| `TL0028` | A module is exposed in a reactive (WebFlux) application, which has no exposure support. | failure | Set `expose.enabled=false`, or use a servlet application. |
| `TL0029` | A `final` handler class (`@Controller`, `@RestController`, `@RequestMapping`) provides an internal module's contract, whatever beans the host has; turning observation off does not avoid it. | failure | Make the class non-final. |
| `TL0040` | A contract's idempotency declarations do not match or are invalid: the descriptor's `idempotent` set differs from the `@Idempotent` annotations on the loaded provided contracts (or the module and API descriptors disagree), a contract places `@Idempotent` on a `default` method or overloads a method name with conflicting `@Idempotent` annotations, or `@Idempotent` is placed on a separate `remote.http-interface` instead of the contract. | failure | Put `@Idempotent` on the contract interface or its abstract methods in the API package with consistent overloads, and rebuild the API and module artifacts with the processor. |
| `TL0041` | Per-group retry or circuit breaker is configured (`remote.retry.max-attempts` above `1` or `remote.circuit-breaker.enabled=true`) without `topolith-spring-cloud`'s resilience configuration on the classpath and active (not excluded) or, for a circuit breaker, without exactly one `CircuitBreakerFactory` bean of a supported implementation (`Resilience4JCircuitBreakerFactory`). | failure | Add `topolith-spring-cloud` (and a single supported factory such as `spring-cloud-starter-circuitbreaker-resilience4j` when `remote.circuit-breaker.enabled=true`), or turn the setting off. |

## Call context and authentication

| Code | Meaning | Severity | Fix |
|---|---|---|---|
| `TL0033` | More than one `ModuleCallObservationConvention` bean exists. | failure | Keep one convention bean. |
| `TL0034` | Context propagation is misconfigured: an invalid or duplicate `topolith.context.mdc-keys` entry, more than 16 keys, a key without SLF4J, or more than one `TenantContext` bean. | failure | Fix the key list, or keep one `TenantContext` bean. |
| `TL0035` | An outgoing MDC value is over 1024 characters once encoded or has a control character, so it is not sent. | warning | Shorten the value or remove the control characters, or stop listing the key. |
| `TL0036` | A module has more than one outgoing-auth hook, or a hook changes an `Authorization` header a client customizer set. | failure | Keep one hook: `topolith.modules.<id>.auth.type` or one `ModuleClients.authenticate` bean. |
| `TL0037` | `auth.type=client-credentials` cannot be satisfied: no OAuth2 client classes, a missing or unknown `auth.registration-id`, or not exactly one `ClientRegistrationRepository` and one `OAuth2AuthorizedClientService` bean. | failure | Add Spring Security's OAuth2 client (`spring-security-oauth2-client`) and a matching client registration. |
| `TL0038` | An outgoing tenant is over 1024 characters once encoded or has a control character, so the call fails before it is sent. | failure | Shorten the tenant id your `TenantContext` returns, or remove its control characters. |
| `TL0039` | A request carrying `Topolith-Tenant` reached a module route of a host with no `TenantContext` bean, so the provider answers 500 without running. | failure | Add a `TenantContext` bean to the provider host. |

## Boundaries

| Code | Meaning | Severity | Fix |
|---|---|---|---|
| `TL0012` | An internal class references another module's non-API class. Suppressed for a violation in the frozen baseline. | failure | Depend on the other module's API. Only violations that already existed when the baseline was frozen are suppressed. |
| `TL0030` | A packaged boundary baseline is malformed, or a second copy exists. | failure | Regenerate it with `TopolithBaseline.generate`. |

## Refresh and readiness

| Code | Meaning | Severity | Fix |
|---|---|---|---|
| `TL0010` | A refreshed mode, URL/service-id wiring change or loopback client setting (including `remote.retry.*` and `remote.circuit-breaker.*`) needs a restart, as does an external module's retry or circuit breaker turned on by a refresh while Spring Cloud's global HTTP-service breaker is applied (D-106). | warning | Restart the host. The running topology is unchanged until then. |
| `TL0011` | Refreshed endpoint, retry or circuit-breaker settings are invalid or the client rebuild failed. | warning | Correct the settings. The old client, retry policy and circuit breaker keep serving. |
| `TL0014` | Cloud context classes are present without Topolith refresh integration. | warning | Add `topolith-spring-cloud` if you want refresh handling. |
| `TL0015` | The remote API-version endpoint is unavailable or inaccessible. | warning | Check the remote provider and its exposure. The host stays ready. |
| `TL0023` | A host API descriptor's `api-version` major differs from its module's descriptor or the remote provider. | warning | Align the API JAR with the module version. |
