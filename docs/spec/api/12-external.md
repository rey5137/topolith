# 12 — External contracts and clients

This file specifies external HTTP clients, discovery, errors, exposure and endpoint refresh.
It also specifies the internal transaction boundary required by the 0.1 plan.

## Rules

### R-EXT-01 — Contract and HTTP service group

By default the `*-api` contract uses `@HttpExchange` and the module's `@RestController` implements it (it may delegate
to a non-contract service); a service-only implementation is exposed through a separate HTTP interface (D-53).
External mode registers one HTTP service group per module for its provided contracts. A separate HTTP
interface remains supported (D-49). Its case-sensitive contract FQN mapping key requires a case-preserving property source;
environment variables are unsupported for these mappings. Implements INV-5, INV-8, D-3 and D-53.

- AC-EXT-01 (M2.1): An external contract call uses the HTTP service group named for its module.
- AC-EXT-02 (M2.4): An annotated contract implemented by the module's `@RestController` is served under the module prefix.
- AC-EXT-03 (M2.5): A separate HTTP interface can serve a contract without `spring-web` in the contract API.

### R-EXT-02 — Endpoint and discovery

Exactly one of `remote.url` and `remote.service-id` is required for an external module; otherwise fail with TL0003.
An `expose.*` key under `topolith.modules.<id>` for an id that no descriptor names fails with TL0003; other settings
of an absent module stay allowed for a shared configuration (a `mode` stays TL0016 or TL0002), and a host HTTP service group named like an external or loopback module that holds an
interface that is not that module's fails with TL0003.
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
- **Provider.** On a handler whose bean type lies in an internal or loopback module's `package`, a declared exception answers
  `application/problem+json` with `type` `urn:topolith:problem:contract-exception`, the status of its
  `@ResponseStatus` (default 500; 503 for `ModuleUnavailableException`), `detail` set to the message (for
  `ModuleUnavailableException` the fixed message below), and the
  properties `exception` (the declared class name) and `module`. On those handlers this takes precedence over the
  host's `@ControllerAdvice`s and the module's own `@ExceptionHandler`s, which would run only in external mode; any
  other exception, and any other handler, is left to the host and Boot, and an undeclared exception's message is not sent by Topolith (it is
  left to the host's error handling, so a host setting `server.error.include-message=always` sends it, and the caller's
  HTTP exception then carries it). If no message converter writes `application/problem+json`, the problem is written
  directly as JSON.
- **Caller.** A Topolith problem naming a declared class the caller can load is rebuilt through its public `(String)`
  or no-arg constructor, carrying the HTTP exception as suppressed. A connection failure, a timeout, a 502, 503 or 504
  without a Topolith problem, no LoadBalancer instance, or a problem naming `ModuleUnavailableException` raise
  `ModuleUnavailableException` with the original exception as cause and the fixed message `module '<id>' could not
  be reached (<kind>)`, so no URL or downstream body travels on; after a read timeout the call may have run. With Spring Cloud
  CircuitBreaker on the group (`spring.cloud.circuitbreaker.http-services.enabled=true`) the translation sees through
  the breaker's no-fallback wrapper (D-51), a time-limiter timeout raises `ModuleUnavailableException` with kind
  `timeout`, and a call rejected by an open circuit raises it with kind `circuit open`; a host's own wrapper exception
  propagates as thrown.
- **Unspecified.** Any other failure (an undeclared exception, a 4xx or 500 without a Topolith problem) raises an
  unchecked exception that is neither a declared type nor `ModuleUnavailableException`; its type is unspecified.

- AC-EXT-09 (M2.3): A declared API exception returns as ProblemDetail and reconstructs the same type at the caller; a
  subclass outside the api packages is rebuilt as its declared ancestor, and an exception from another module's api
  package passing through the provider is rebuilt too, as is one thrown after the handler started its answer.
- AC-EXT-10 (M2.3): A transport failure raises `ModuleUnavailableException` at the caller: connection refused, a read
  timeout (including a Spring Cloud CircuitBreaker time-limiter timeout), a 503 without a Topolith problem, zero LoadBalancer instances, an open circuit, and a provider's own
  `ModuleUnavailableException` (transitive); its message names only the module and the kind of failure, never a URL
  or a downstream body.
- AC-EXT-29 (M2.3): An undeclared exception is not rebuilt, and Topolith does not send its message (the host's
  `server.error.include-message` setting still applies).
- AC-EXT-30 (M2.3): A contract method declaring a checked exception, or a concrete exception class in an api package
  that a caller cannot rebuild or whose `@ResponseStatus` is not 4xx or 5xx, fails startup with TL0027 for an internal, loopback or external module, in a monolith too; a
  `none` module is not checked.
- AC-EXT-31 (M2.3): The provider resolver exists only with an internal module, and the caller advice only with an
  external one; a `none` module leaves neither; a host's own service groups and a host controller's advices are
  untouched, except that a group named like an external module is Topolith's and fails with TL0003 if it holds another
  interface.
- AC-EXT-32 (M2.3): A host `@ControllerAdvice` does not change what a module route sends for a declared exception.

### R-EXT-05 — Exposure

Each module's exposed endpoints use the fixed `/<id>` prefix. `topolith.modules.<id>.expose.enabled` controls exposure of
all an internal module's routes and is `false` in every topology, so a monolith serves none; a loopback module's routes
are always mapped (R-MODE-06, D-76). A module that is neither internal nor loopback, or a reactive host, cannot be
exposed (TL0003, TL0028; D-44). Exposed endpoints have a security hook: the public
`ExposedEndpoints` and `ExposedRequestMatcher` beans, for the host's own `SecurityFilterChain` (D-46). One application
has one discovery registration. Implements INV-8 and P-2.

- AC-EXT-11 (M2.4): An exposed module's endpoint uses its fixed id prefix in each topology.
- AC-EXT-12 (M2.4): A monolith defaults exposure off, and disabling exposure removes that module's routes.
- AC-EXT-13 (M2.4): A host can supply security through the exposed-endpoint hook: a matcher over every exposed prefix
  for its own `SecurityFilterChain`, and no chain registered by Topolith.
- AC-EXT-14 (M2.4): An application exposing multiple modules registers once with discovery.

### R-EXT-06 — Client customization

The host can configure a module client with `ModuleClients.configure(<id>, <customizer>)`. A host status
handler throwing its own exception bypasses Topolith's error translation. The customizer cannot replace the base
URL, URI factory, default URI variables, request factory, interceptors or message converters (D-48, D-50). Implements P-2.

- AC-EXT-15 (M2.5): Custom client configuration applies to the named module's group.

### R-EXT-07 — Internal transaction boundary

An internal contract call suspends the caller's transaction with `NOT_SUPPORTED`, for every caller and in front of the
callee's own transaction interceptor; the callee's own transaction annotation decides whether it starts one. An active
caller transaction increments the counter `topolith.contract.calls.in.transaction` (tags `module`, `contract`). Implements
INV-6, D-2, D-47. A `final` handler class (a `@Controller`, `@RestController` or `@RequestMapping` type) that is a provider of an
internal module implementing a provided contract fails startup with TL0029 when a transaction manager exists; a
`final` provider that is not a handler takes an interface proxy.

- AC-EXT-16 (M2.4): A caller transaction is suspended during an internal contract call and resumes afterward; a final
  handler provider class fails startup with TL0029, while a final provider that is not a handler takes the boundary.
- AC-EXT-17 (M2.4): The callee's own transaction policy applies and an active caller transaction emits a warning metric.

### R-EXT-08 — Endpoint refresh

With `topolith-spring-cloud`, an `EnvironmentChangeEvent` changing an external module's URL, service id to another
service id, or timeouts rebuilds its client. Validate settings before swapping; in-flight calls finish on the old
client. On invalid settings or rebuild failure keep the old client, log TL0011 and report the failure on the actuator.
A URL-to-service-id or service-id-to-URL switch instead uses TL0010 and restart-pending behavior in R-MODE-05.
Without Spring Cloud on the classpath there is no refresh handling. A host's own unguarded
`RestClientHttpServiceGroupConfigurer` setting `requestFactory` for all groups may win over the swap (same lowest order),
so refreshed timeouts would not apply; a refresh changing only Boot keys (`spring.http.serviceclient.<id>.*`,
`spring.http.clients.*`) is restart-only and unchecked until the next start (D-50). Implements INV-4, INV-9, D-11 through D-16, D-20.

- AC-EXT-18 (M2.6): Changing the URL rebuilds the client.
- AC-EXT-19 (M2.6): Changing timeout settings rebuilds the client.
- AC-EXT-20 (M2.6): Changing one service id to another rebuilds the client's service group.
- AC-EXT-21 (M2.6): A call in flight during a successful client swap finishes on the old client.
- AC-EXT-22 (M2.6): Switching either direction between URL and service id logs TL0010 and marks restart pending.
- AC-EXT-23 (M2.6): Invalid endpoint settings leave the old client usable, log TL0011 and surface failure on actuator.
- AC-EXT-24 (M2.6): A failed client rebuild leaves the old client usable, logs TL0011 and surfaces failure on actuator.

### R-EXT-09 — Loopback calls

A call to a loopback module's contract (R-MODE-06) takes the external client path end to end: the group's client
serializes the arguments, the request reaches the module's route on the host's own server through the servlet stack
(filters, security, argument resolution and validation), and the caller reads the result back from the response.
R-EXT-03's timeouts and defaults, R-EXT-04's provider and caller sides (ProblemDetail, rebuilt declared exceptions,
`ModuleUnavailableException` for transport failures, with the caller's translation present because a loopback module
exists) and R-EXT-06's customization apply as for an external module. The provider runs on a server request thread, so
the caller's transaction never reaches it (D-2); R-EXT-07's boundary on the provider applies as for an internal module
and sees no caller transaction, so `topolith.contract.calls.in.transaction` does not count a loopback call.
Implements INV-6, D-2, D-43 and D-75.

- AC-EXT-33 (M5.3): A loopback contract call reaches the module's route over HTTP on the host's own port: the provider
  receives a deserialized copy of each argument and the caller a deserialized result, so a provider that mutates its
  argument or later mutates the object it returned changes neither the caller's argument nor the caller's result.
- AC-EXT-34 (M5.3): A loopback call rebuilds a declared exception as its declared type, leaves an undeclared one
  unrebuilt, and raises `ModuleUnavailableException` on a read timeout, as AC-EXT-09, AC-EXT-29 and AC-EXT-10 require
  for an external call.
- AC-EXT-35 (M5.3): A loopback call made inside a caller transaction runs outside it.
- AC-EXT-40 (M5.3): An unconfigured loopback group uses R-EXT-03's two-second connect and five-second read timeouts,
  and its `remote.connect-timeout` and `remote.read-timeout` overrides apply.
- AC-EXT-41 (M5.3): `ModuleClients.configure(<id>, ...)` applies to a loopback module's group.

### R-EXT-10 — Loopback latency and fault injection

A loopback module can delay its calls and fail a share of them, set under `topolith.modules.<id>.loopback`. Injection
happens in the loopback client, so a call from anything else (a browser, a test's own HTTP client) is unaffected.

| Key | Type and default | Effect |
|---|---|---|
| `latency` | duration, `0` | Delay after the provider answers, before the caller reads the response (sent calls only) |
| `fault.rate` | decimal in `[0, 1]`, `0` | Share of calls that fail with `fault.kind` |
| `fault.kind` | `connection-failure` (default), `unavailable` or `timeout` | How a chosen call fails |
| `fault.seed` | long, `0` | Seed of the module's fault sequence |

- **Faults.** A fault surfaces as an R-EXT-04 transport failure through the same translation as a real one, so the
  caller sees `ModuleUnavailableException` with its fixed message. `connection-failure`: the call is not sent; kind
  `connection failure`. `unavailable`: the call is not sent and the client receives a 503 without a Topolith problem;
  kind `status 503`. `timeout`: the call is sent and the provider runs, then the response is discarded after
  `latency` (at most the read timeout); kind `timeout`, as after a real read timeout.
- **Latency.** A latency at or above the group's read timeout makes the call wait the read timeout and fail with kind
  `timeout`, the provider having run. A call that fails with `connection-failure` or `unavailable` is not delayed.
- **Determinism.** The calls that fail are drawn, in the order the module's calls are sent, from a pseudo-random
  sequence fixed by `fault.seed`: rate `0` fails none, rate `1` fails every call, and the same seed, rate and call
  order fail the same calls in every run. With `topolith-spring-test`, its test execution listener restarts every
  loopback module's sequence in `beforeTestMethod`, so calls made in `@BeforeEach` count toward that method and a test
  method fails the same calls whether it runs alone or after others sharing the cached context; without it the
  sequence runs once per context. Calls sent concurrently draw in no defined order. The restart is not public API: it
  lives in an internal package (D-78).
- **Validation.** A `loopback.*` key for a module that is not `loopback`, an unknown `loopback.*` key, or an invalid
  value (a negative or unparsable latency, a rate outside `[0, 1]`, an unknown kind, a seed that is not a long) fails
  with TL0003 naming the key. The keys are restart-only (R-MODE-06, D-77).

Implements INV-4, INV-6, D-43, D-74 and D-78.

- AC-EXT-36 (M5.4): A loopback call with `latency` returns no sooner than that latency after the provider answered; a
  latency at or above the read timeout raises `ModuleUnavailableException` with kind `timeout` after the read timeout,
  the provider having run.
- AC-EXT-37 (M5.4): At rate `1`, `connection-failure` and `unavailable` raise `ModuleUnavailableException` with kinds
  `connection failure` and `status 503` without calling the provider, and `timeout` raises it with kind `timeout`
  after the provider ran; each message names only the module and the kind.
- AC-EXT-38 (M5.4): Rate `0` fails no call and rate `1` every call; with a seed and a rate between them, the same test
  method fails the same calls whether it runs alone or after other methods sharing the cached context.
- AC-EXT-39 (M5.4): A `loopback.*` key for an internal, external or `none` module, an unknown `loopback.*` key and each
  invalid value fail with TL0003 naming the key.
