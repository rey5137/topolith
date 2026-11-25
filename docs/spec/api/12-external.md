# 12 — External contracts and clients

This file specifies external HTTP clients, discovery, errors, exposure and endpoint refresh.
It also specifies the internal transaction boundary required by the 0.1 plan.

## Rules

### R-EXT-01 — Contract and HTTP service group

By default the `*-api` contract uses `@HttpExchange`, a service implements it, and a thin `@RestController`
exposes it. External mode registers one HTTP service group per module for its provided contracts. A separate HTTP
interface remains supported. Its case-sensitive contract FQN mapping key requires a case-preserving property source;
environment variables are unsupported for these mappings. Implements INV-5, INV-8 and D-3.

- AC-EXT-01 (M2.1): An external contract call uses the HTTP service group named for its module.
- AC-EXT-02 (M2.4): An annotated contract implemented by a service is exposed through a thin controller.
- AC-EXT-03 (M2.5): A separate HTTP interface can serve a contract without `spring-web` in the contract API.

### R-EXT-02 — Endpoint and discovery

Exactly one of `remote.url` and `remote.service-id` is required for an external module; otherwise fail with TL0003.
Map `remote.url` plus the provider's fixed `/<id>` prefix to `spring.http.serviceclient.<id>.base-url` (D-45): a
contract's `@HttpExchange` paths are relative to that prefixed URL. Service ids use `lb://<service-id>/<id>` and
Spring Cloud LoadBalancer
with a `DiscoveryClient`; if the integration is absent, fail with TL0009 and name the starter to add.
Implements INV-4, INV-8 and P-3.

- AC-EXT-04 (M2.1): A fixed URL configures the corresponding service group's base URL and handles a contract call.
- AC-EXT-25 (M2.1): Differing explicitly configured Topolith and Boot client properties fail with TL0003 naming
  both keys; an equal URL (case-insensitive scheme and host, trailing `/` ignored) is not a conflict, and an
  unparsable value fails with TL0003 naming the key and value.
- AC-EXT-26 (M2.1): An external module with no HTTP client stack (spring-web, or Boot's service-client
  auto-configuration) fails with TL0026 naming `spring-boot-starter-restclient`.
- AC-EXT-27 (M2.1): A provided contract that cannot be loaded fails with TL0026 naming the module and contract.
- AC-EXT-28 (M2.1): A provided contract with no `@HttpExchange` (type or method) fails with TL0026 (D-41).
- The endpoint-selection startup criterion is AC-VAL-01.
- AC-EXT-05 (M2.2): A service id maps to `lb://<service-id>` and resolves through LoadBalancer and a configured
  `DiscoveryClient`; an explicit differing Boot `base-url` fails with TL0003 naming both keys (D-42).
- AC-EXT-06 (M2.2): Service-id configuration without the required Cloud integration fails with TL0009 naming the
  module and `spring-cloud-starter-loadbalancer`; a URL-only module is unaffected (D-42).

### R-EXT-03 — Timeouts

Every group has connect and read timeouts, defaulting to two and five seconds respectively; each group can override
both. Set `topolith.modules.<id>.remote.connect-timeout` and `remote.read-timeout`, or the corresponding Boot
group properties; Boot-only values, including the global `spring.http.clients.*` timeouts, replace the defaults. Implements INV-4, P-2 and D-40.

- AC-EXT-07 (M2.1): An unconfigured client uses a two-second connect timeout and a five-second read timeout.
- AC-EXT-08 (M2.1): Group timeout overrides apply to that group's client.

### R-EXT-04 — Errors

Declared exceptions cross HTTP as ProblemDetail and are reconstructed as the same exception type; transport
failures raise `ModuleUnavailableException`. Implements INV-2 (AC-EXT-31), INV-6, D-3 and D-43.

- **Declared.** An unchecked exception is declared when its class, or its nearest non-abstract superclass, lies in an
  api package the host knows (exact packages, D-27). Only the top-level exception counts, never its cause chain.
  `ModuleUnavailableException` is always declared, so a provider's own failed call reaches its caller unchanged.
- **Provider.** On a handler whose bean type lies in an internal module's `package`, a declared exception answers
  `application/problem+json` with `type` `urn:topolith:problem:contract-exception`, the status of its
  `@ResponseStatus` (default 500; 503 for `ModuleUnavailableException`), `detail` set to the message (for
  `ModuleUnavailableException` the fixed message below), and the
  properties `exception` (the declared class name) and `module`. On those handlers this takes precedence over the
  host's `@ControllerAdvice`s and the module's own `@ExceptionHandler`s, which would run only in external mode; any
  other exception, and any other handler, is left to the host and Boot, and an undeclared exception's message is never
  sent.
- **Caller.** A Topolith problem naming a declared class the caller can load is rebuilt through its public `(String)`
  or no-arg constructor, carrying the HTTP exception as suppressed. A connection failure, a timeout, a 502, 503 or 504
  without a Topolith problem, no LoadBalancer instance, or a problem naming `ModuleUnavailableException` raise
  `ModuleUnavailableException` with the original exception as cause and the fixed message `module '<id>' could not
  be reached (<kind>)`, so no URL or downstream body travels on; after a read timeout the call may have run.
- **Unspecified.** Any other failure (an undeclared exception, a 4xx or 500 without a Topolith problem) raises an
  unchecked exception that is neither a declared type nor `ModuleUnavailableException`; its type is unspecified.

- AC-EXT-09 (M2.3): A declared API exception returns as ProblemDetail and reconstructs the same type at the caller; a
  subclass outside the api packages is rebuilt as its declared ancestor, and an exception from another module's api
  package passing through the provider is rebuilt too, as is one thrown after the handler started its answer.
- AC-EXT-10 (M2.3): A transport failure raises `ModuleUnavailableException` at the caller: connection refused, a read
  timeout, a 503 without a Topolith problem, zero LoadBalancer instances, and a provider's own
  `ModuleUnavailableException` (transitive); its message names only the module and the kind of failure, never a URL
  or a downstream body.
- AC-EXT-29 (M2.3): An undeclared exception is not rebuilt, and its message does not reach the caller.
- AC-EXT-30 (M2.3): A contract method declaring a checked exception, or a concrete exception class in an api package
  that a caller cannot rebuild or whose `@ResponseStatus` is not 4xx or 5xx, fails startup with TL0027 for an internal or external module, in a monolith too; a
  `none` module is not checked.
- AC-EXT-31 (M2.3): The provider resolver exists only with an internal module, and the caller advice only with an
  external one; a `none` module leaves neither; a host's own service groups and a host controller's advices are
  untouched.
- AC-EXT-32 (M2.3): A host `@ControllerAdvice` does not change what a module route sends for a declared exception.

### R-EXT-05 — Exposure

Each module's exposed endpoints use the fixed `/<id>` prefix. `topolith.modules.<id>.expose.enabled` controls exposure of
all the module's routes and is `false` in every topology, so a monolith serves none; a module that is not internal, or
a reactive host, cannot be exposed (TL0003, TL0028; D-44). Exposed endpoints have a security hook: the public
`ExposedEndpoints` and `ExposedRequestMatcher` beans, for the host's own `SecurityFilterChain` (D-46). One application
has one discovery registration. Implements INV-8 and P-2.

- AC-EXT-11 (M2.4): An exposed module's endpoint uses its fixed id prefix in each topology.
- AC-EXT-12 (M2.4): A monolith defaults exposure off, and disabling exposure removes that module's routes.
- AC-EXT-13 (M2.4): A host can supply security through the exposed-endpoint hook: a matcher over every exposed prefix
  for its own `SecurityFilterChain`, and no chain registered by Topolith.
- AC-EXT-14 (M2.4): An application exposing multiple modules registers once with discovery.

### R-EXT-06 — Client customization

The host can configure a module client with `ModuleClients.restClientBuilder(<id>, <customizer>)`. A host status
handler throwing its own exception bypasses Topolith's error translation. Implements P-2.

- AC-EXT-15 (M2.5): Custom client configuration applies to the named module's group.

### R-EXT-07 — Internal transaction boundary

An internal contract call suspends the caller's transaction with `NOT_SUPPORTED`, for every caller and in front of the
callee's own transaction interceptor; the callee's own transaction annotation decides whether it starts one. An active
caller transaction increments the counter `topolith.contract.calls.in.transaction` (tags `module`, `contract`). Implements
INV-6, D-2, D-47.

- AC-EXT-16 (M2.4): A caller transaction is suspended during an internal contract call and resumes afterward.
- AC-EXT-17 (M2.4): The callee's own transaction policy applies and an active caller transaction emits a warning metric.

### R-EXT-08 — Endpoint refresh

With `topolith-spring-cloud`, an `EnvironmentChangeEvent` changing an external module's URL, service id to another
service id, or timeouts rebuilds its client. Validate settings before swapping; in-flight calls finish on the old
client. On invalid settings or rebuild failure keep the old client, log TL0011 and report the failure on the actuator.
A URL-to-service-id or service-id-to-URL switch instead uses TL0010 and restart-pending behavior in R-MODE-05.
Without Spring Cloud on the classpath there is no refresh handling. Implements INV-4, INV-9, D-11 through D-16, D-20.

- AC-EXT-18 (M2.6): Changing the URL rebuilds the client.
- AC-EXT-19 (M2.6): Changing timeout settings rebuilds the client.
- AC-EXT-20 (M2.6): Changing one service id to another rebuilds the client's service group.
- AC-EXT-21 (M2.6): A call in flight during a successful client swap finishes on the old client.
- AC-EXT-22 (M2.6): Switching either direction between URL and service id logs TL0010 and marks restart pending.
- AC-EXT-23 (M2.6): Invalid endpoint settings leave the old client usable, log TL0011 and surface failure on actuator.
- AC-EXT-24 (M2.6): A failed client rebuild leaves the old client usable, logs TL0011 and surfaces failure on actuator.
