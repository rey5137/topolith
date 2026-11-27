# 12 — External contracts and clients

This file specifies external HTTP clients, discovery, errors, exposure and endpoint refresh.
It also specifies the internal transaction boundary, loopback calls, the call observation, context propagation,
outgoing authentication, per-group retry and the per-group circuit breaker.

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
group properties; Boot-only values, including the global `spring.http.clients.*` timeouts, replace the defaults. The
group's other Boot settings (`ssl.bundle`, redirects) apply to its client as Boot maps them (D-50). Implements INV-4,
P-2 and D-40.

- AC-EXT-07 (M2.1): An unconfigured client uses a two-second connect timeout and a five-second read timeout.
- AC-EXT-08 (M2.1): Group timeout overrides apply to that group's client.

### R-EXT-04 — Errors

Declared exceptions cross HTTP as ProblemDetail and are reconstructed as the same exception type; transport
failures raise `ModuleUnavailableException`. Implements INV-2 (AC-EXT-31), INV-6, D-3, D-43, D-116 and D-117.

- **Declared.** An unchecked exception is declared when its class, or its nearest non-abstract superclass, lies in an
  api package the host knows (exact packages, D-27). Only the top-level exception counts, never its cause chain.
  `ModuleUnavailableException` is always declared, so a provider's own failed call reaches its caller unchanged.
  `IllegalArgumentException`, `IllegalStateException` and `UnsupportedOperationException` are declared for every
  module, and a subclass of one outside the api packages is declared as its nearest listed class (D-117). A checked
  exception class named in a contract method's `throws` clause must itself lie in an api package (TL0027 otherwise);
  a checked exception the provider throws is then declared as R-EXT-04's declared class, the thrown class in an api
  package or else its nearest non-abstract superclass there, which is the named class or a subclass of it, so the
  method can throw it (D-116). It crosses and is rebuilt like an unchecked one. An exception's other fields and its
  cause chain never cross.
- **Provider.** On a handler whose bean type lies in an internal or loopback module's `package`, a declared exception answers
  `application/problem+json` with `type` `urn:topolith:problem:contract-exception`, the status of its
  `@ResponseStatus` (default 500; 503 for `ModuleUnavailableException`), `detail` set to the message (for
  `ModuleUnavailableException` the fixed message below), and the
  properties `exception` (the declared class name) and `module`. Before such a handler runs, once R-EXT-12's tenant
  check has passed, Topolith sets the response header `Topolith-Module: <id>`, so every response the handler produces,
  an error included, is marked as the module's own (D-117); a handler or filter that resets the response drops it.
  On those handlers this takes precedence over the
  host's `@ControllerAdvice`s and the module's own `@ExceptionHandler`s, which would run only in external mode; any
  other exception, and any other handler, is left to the host and Boot, and an undeclared exception's message is not sent by Topolith (it is
  left to the host's error handling, so a host setting `server.error.include-message=always` sends it, and the caller's
  HTTP exception then carries it). If no message converter writes `application/problem+json`, the problem is written
  directly as JSON.
- **Caller.** A Topolith problem naming a declared class the caller can load is rebuilt through its public `(String)`
  or no-arg constructor, carrying the HTTP exception as suppressed. A connection failure, a timeout, a 502, 503 or 504
  without a Topolith problem, no LoadBalancer instance, or a problem naming `ModuleUnavailableException` raise
  `ModuleUnavailableException` with the original exception as cause and the fixed message `module '<id>' could not
  be reached (<kind>)`, so no URL or downstream body travels on; after a read timeout the call may have run. With a
  Spring Cloud CircuitBreaker on the group (R-EXT-15's per-group breaker or
  `spring.cloud.circuitbreaker.http-services.enabled=true`) the translation sees through the breaker's no-fallback
  wrapper (D-51), a time-limiter timeout raises `ModuleUnavailableException` with kind `timeout`, and a call rejected
  by an open circuit raises it with kind `circuit open`; a host's own wrapper exception propagates as thrown. A 401,
  403, 404, 405 or 415 without a Topolith problem and without the `Topolith-Module` header (a missing route, a method
  or media type the route does not take, the host's security chain, or R-EXT-12's tenant rejection: the module's
  handler never ran) raises `ModuleUnavailableException` with kind `status <code>` and the HTTP exception as cause
  (D-117); R-EXT-14 and R-EXT-15 still ignore these statuses (no retry, no breaker failure). The same status carrying
  the header came from the module's handler (a `ResponseStatusException`, method security, an undeclared
  `@ResponseStatus` exception), so it is undeclared, as in internal mode. A caller rebuilds a checked exception and
  throws it from the contract method that declares it.
- **Unspecified.** Any other failure (an undeclared exception, a 4xx carrying the `Topolith-Module` header, any other
  4xx, or a 500 without a Topolith problem) raises an unchecked exception that is neither a declared type nor
  `ModuleUnavailableException`; its type is unspecified.

- AC-EXT-09 (M2.3): A declared API exception returns as ProblemDetail and reconstructs the same type at the caller; a
  subclass outside the api packages is rebuilt as its declared ancestor, and an exception from another module's api
  package passing through the provider is rebuilt too, as is one thrown after the handler started its answer.
- AC-EXT-10 (M2.3): A transport failure raises `ModuleUnavailableException` at the caller: connection refused, a read
  timeout (including a Spring Cloud CircuitBreaker time-limiter timeout), a 503 without a Topolith problem, zero LoadBalancer instances, an open circuit, and a provider's own
  `ModuleUnavailableException` (transitive); its message names only the module and the kind of failure, never a URL
  or a downstream body.
- AC-EXT-29 (M2.3): An undeclared exception is not rebuilt, and Topolith does not send its message (the host's
  `server.error.include-message` setting still applies).
- AC-EXT-30 (M2.3): A contract method declaring a checked exception outside its api packages (from M8.5, D-116; any
  checked exception before), or a concrete exception class in an api package
  that a caller cannot rebuild or whose `@ResponseStatus` is not 4xx or 5xx, fails startup with TL0027 for an internal, loopback or external module, in a monolith too; a
  `none` module is not checked.
- AC-EXT-31 (M2.3): The provider resolver exists only with an internal module, and the caller advice only with an
  external one; a `none` module leaves neither; a host's own service groups and a host controller's advices are
  untouched, except that a group named like an external module is Topolith's and fails with TL0003 if it holds another
  interface.
- AC-EXT-32 (M2.3): A host `@ControllerAdvice` does not change what a module route sends for a declared exception.
- AC-EXT-70 (M8.5): A contract method declaring a checked exception from its api package starts in every topology; the
  provider's checked exception reaches a loopback and an external caller as the same type with its message, thrown
  from the contract method, as it does in internal mode and through the by-value proxy.
- AC-EXT-71 (M8.5): A contract method whose `throws` clause names a checked exception class outside the api packages
  (even one extending an api-package exception), or a checked exception class in an api package that a caller cannot
  rebuild, fails startup with TL0027; a provider throwing a subclass, outside the api packages, of a named api-package
  checked exception reaches the caller as the named class, thrown from the contract method.
- AC-EXT-72 (M8.5): A 401, 403, 404, 405 and 415 without a Topolith problem and without the `Topolith-Module` header
  (an unmapped path, a wrong method or media type, the host's security chain) each raise `ModuleUnavailableException`
  with kind `status <code>`, naming only the module, in loopback and external mode; none is retried or recorded by the
  circuit breaker; a 400, 409 or 500 without a problem still raises the unspecified exception. A provider handler
  throwing `ResponseStatusException(NOT_FOUND)`, an `AccessDeniedException` from method security, or an undeclared
  `@ResponseStatus(404)` exception answers with the header, and the caller receives the unspecified exception with
  R-EXT-11's `exception` `undeclared`, as in internal mode.
- AC-EXT-73 (M8.5): An `IllegalArgumentException`, `IllegalStateException`, `UnsupportedOperationException` and a
  `NumberFormatException` thrown by a provider reach a loopback and an external caller as the first three types and
  `IllegalArgumentException` respectively, with their messages, and R-EXT-11's `exception` names the same class in
  every mode.
- AC-EXT-74 (M8.5): A `TenantContext` rejection (403) reaches the loopback or external caller as
  `ModuleUnavailableException` with kind `status 403`.

### R-EXT-05 — Exposure

Each module's exposed endpoints use the fixed `/<id>` prefix. `topolith.modules.<id>.expose.enabled` controls exposure of
all an internal module's routes and is `false` in every topology, so a monolith serves none; a loopback module's routes
are always mapped (R-MODE-06, D-76). A module that is neither internal nor loopback, or a reactive host, cannot be
exposed (TL0003, TL0028; D-44). Exposed endpoints have a security hook: the public
`ExposedEndpoints` and `ExposedRequestMatcher` beans, for the host's own `SecurityFilterChain` (D-46). One application
has one discovery registration. Implements INV-8, P-2, D-125 and D-126.

- **Route coverage (D-126).** At startup, for each module that is internal and exposed, or loopback, every abstract
  method of every contract it provides must be served by a module route: a handler method of a bean the module owns
  (by definition) that implements that contract method, or, from M8.6, a route Topolith registers for a listed
  contract. An uncovered method, for example an inferred contract implemented only by a non-handler `@Service`, fails
  startup with TL0044 (new, M8.3) naming the module, contract and method, and the fix (a module `@RestController`
  implementing the contract, D-53). An unexposed internal module and a `none` or `external` module are not checked.
- **Routes for listed contracts (D-125, from M8.6).** For a contract listed in a module declaration's `provides`
  (R-DESC-02) of a module that is internal and exposed, or loopback, Topolith registers one route per abstract contract
  method, invoking the module's bean of that contract, selected by bean-definition ownership and never by type (so an
  external module's client proxy, which is not module-owned, is never picked). The `RequestMappingInfo` comes from
  Spring's own `getMappingForMethod(method, contract)` on a private, non-bean `RequestMappingHandlerMapping` subclass
  that copies the host mapping's configuration (pattern parser, content negotiation, API version strategy) and the
  `/<id>` prefix and detects no handlers itself; it is registered on the host's mapping with `registerMapping` from a
  `SmartInitializingSingleton`, before the server starts. No functional route is used. Every abstract contract method
  must carry an `@HttpExchange`-family annotation, or startup fails with TL0026 naming the method (AC-EXT-28's
  meaning); a path clash with another handler fails with TL0005 naming both. A registry maps each registered handler
  to its module and is consulted first wherever Topolith decides a handler's module, so the route gets the `/<id>`
  prefix, R-EXT-12's tenant check, D-117's `Topolith-Module` header, R-EXT-04's problem translation and exactly one
  R-EXT-11 observation per call, as a module controller does; the route invokes the context bean, so R-EXT-07's
  boundary and the by-value proxy apply. A `BeanPostProcessor` on `RequestMappingHandlerAdapter` puts a delegating
  return-value handler first, only for those registered methods, which writes the result as `@ResponseBody` would.
  AOT reflection hints cover the contract methods and their binding types. A `none` module has no definition and an
  unexposed internal module no route, so neither gets anything (INV-2).

- AC-EXT-11 (M2.4): An exposed module's endpoint uses its fixed id prefix in each topology.
- AC-EXT-12 (M2.4): A monolith defaults exposure off, and disabling exposure removes that module's routes.
- AC-EXT-13 (M2.4): A host can supply security through the exposed-endpoint hook: a matcher over every exposed prefix
  for its own `SecurityFilterChain`, and no chain registered by Topolith.
- AC-EXT-14 (M2.4): An application exposing multiple modules registers once with discovery.
- AC-EXT-82 (M8.3): An exposed internal module, and a loopback one, whose provided contract is implemented only by a
  non-handler `@Service` fail startup with TL0044 (new, M8.3) naming the module, contract and method; a module whose
  `@RestController` implements every contract method starts, and the same module unexposed, `none` or `external` is
  not checked.
- AC-EXT-80 (M8.6): A contract listed in `provides` and provided by a `@Bean` lambda, with the module loopback or
  exposed, starts and is served under `/<id>`: in internal, loopback and external mode a `String` result is equal, a
  declared exception is rebuilt as its declared type, a `TenantContext` rejection reaches the caller as
  `ModuleUnavailableException` with kind `status 403`, a handler error carries the `Topolith-Module` header, and each
  call records exactly one `topolith.module.call` observation.
- AC-EXT-81 (M8.6): A listed contract method without an `@HttpExchange`-family annotation fails startup with TL0026
  naming the method, and a registered route clashing with another handler fails with TL0005 naming both; the route
  invokes the module-owned bean even when an external module's client proxy of the same contract type exists; an
  unexposed internal module, a `none` module and an `external` module register no route (INV-2).

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
internal module implementing a provided contract fails startup with TL0029, whether or not a transaction manager
exists (D-89); a `final` provider that is not a handler takes an interface proxy.

- AC-EXT-16 (M2.4): A caller transaction is suspended during an internal contract call and resumes afterward; a final
  handler provider class fails startup with TL0029, while a final provider that is not a handler takes the boundary.
- AC-EXT-17 (M2.4): The callee's own transaction policy applies and an active caller transaction emits a warning metric.

### R-EXT-08 — Endpoint refresh

With `topolith-spring-cloud`, an `EnvironmentChangeEvent` changing an external module's URL, service id to another
service id, timeouts, `remote.retry.*` or `remote.circuit-breaker.*` rebuilds only the affected part of that module's
client (R-EXT-14, R-EXT-15, D-104): changing URL, service id or timeouts swaps the endpoint delegates without
rebuilding or resetting the module's retry policy or circuit breaker; changing `remote.retry.*` swaps only the retry
policy without rebuilding or resetting the circuit breaker; and changing `remote.circuit-breaker.*` rebuilds only the
circuit breaker, which resets its state to closed with an empty window under the new settings (or removes it when
`enabled` becomes `false`). Validate settings before swapping; in-flight calls finish on the old client, policy and
breaker. On invalid settings or rebuild failure keep the old client, policy and breaker, log TL0011 and report the
failure on the actuator.
A URL-to-service-id or service-id-to-URL switch instead uses TL0010 and restart-pending behavior in R-MODE-05.
Without Spring Cloud on the classpath there is no refresh handling. A host's own unguarded
`RestClientHttpServiceGroupConfigurer` setting `requestFactory` for all groups may win over the swap (same lowest order),
so refreshed timeouts would not apply; a refresh changing only Boot keys (`spring.http.serviceclient.<id>.*`,
`spring.http.clients.*`) is restart-only and unchecked until the next start (D-50). A rebuild also gives a
client-credentials hook's token request the new timeouts (D-96). Implements INV-4, INV-9, D-11 through D-15, D-20 and
D-104.

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

### R-EXT-11 — Call observation

Each call to a contract of an internal, loopback or external module made from outside that module is one Micrometer
Observation named `topolith.module.call`, created on the caller's side in every mode; nothing else Topolith does
creates a second one for the same call (D-86). It is created through the context's `ObservationRegistry` bean; without
one nothing is observed and nothing fails. A host turns it off with Micrometer's own means (an `ObservationPredicate`,
or Boot's `management.observations.enable.topolith=false`); Topolith has no property for it (D-89).

- **Where.** External and loopback: around the call through the group's client, so it covers R-EXT-10's latency and
  faults and ends after R-EXT-04's translation. Internal: an advice on the provider bean, in front of R-EXT-07's
  boundary and present without a transaction manager; a `final` handler provider fails with TL0029 whatever beans
  exist (D-89). A loopback module's provider gets no such advice.
  An exposed internal module's route handler is the advised provider, so Topolith marks each request to a module
  route with its handler when its route handling starts, and the advice, before any stack walk, does not observe the
  invocation of that handler method on that bean; calls the handler then makes are observed as usual. A loopback or
  external call is therefore counted once, by its caller. The call runs in the observation's scope: Spring's
  `http.client.requests` observation, and in internal mode the provider's own observations, are its children. That holds when
  the HTTP exchange runs on another thread, as a Spring Cloud CircuitBreaker's time limiter does: the caller's thread
  captures the current observation on the request and the innermost exchange adapter decorator reopens it on the thread
  that runs the exchange, so the client observation, and the W3C trace headers it sends, keep their parent. On the
  provider's side `http.server.requests` continues the trace that Micrometer Tracing propagates in W3C headers;
  Topolith specifies no trace headers of its own.
- **Name and keys.** The contextual (span) name is `<module.id> <contract simple name>.<method>`, the same in every
  mode. Low-cardinality keys: `module.id`; `module.mode` (`internal`, `loopback` or `external`); `caller.module`;
  `contract` (the contract's fully qualified name); `method` (the method name); and `exception`: `none` on success,
  the simple name of the declared class the caller receives for a declared exception (R-EXT-04: the thrown class
  itself when it lies in an api package, else its nearest non-abstract superclass there, so
  `ModuleUnavailableException` for a transport failure), and `undeclared` for any other exception. There are no
  high-cardinality keys. The observation records the exception the caller receives as its error (D-87).
- **Caller.** `caller.module` is the module whose `package` contains the class of the nearest caller frame on the
  calling thread (D-85's package rule, the longest package winning). A stack walk finds that frame: a frame in a
  known module's package is the caller; otherwise frames of the JDK, Spring, Micrometer, Topolith's own library
  packages, Mockito and Byte Buddy, the servlet containers (Tomcat, Jetty, Undertow) and generated proxy classes are
  skipped, and the first other frame, or none, is host code, tagged `_host` (a module id cannot start with `_`). The
  walk runs only for an observation that is not a no-op. A frame
  in the called contract's own package (a default method running on a client proxy) counts as the called module. A
  module's call to its own contract is not observed: in internal mode the advice sees it, but loopback hands that
  caller the provider and an external module's code is not in the host (D-88).
- **Convention.** The public `@Incubating` `ModuleCallObservationConvention` (an `ObservationConvention` of
  `ModuleCallContext`), `ModuleCallContext` and `DefaultModuleCallObservationConvention` live in
  `io.github.rey5137.topolith.spring.boot.observation`. `ModuleCallContext` exposes the module id, its `Mode`, the
  caller module, the contract, the method and the `exception` value. A single host bean of the convention type
  replaces the default, a `GlobalObservationConvention` for `ModuleCallContext` registered with the registry applies
  as Micrometer defines, and two convention beans fail startup with TL0033 (D-89).
- **Activation.** The advice exists only with an internal module and the client instrumentation only on external and
  loopback groups; a `none` module gets neither, and Topolith registers no observation bean for it (INV-2).

- **Error tag (D-123).** The `exception` key is the parity contract of a call's error. Micrometer's meter handler
  adds its own `error` tag from the observation's error, the exception the caller receives, so `error` names the
  thrown subclass in internal mode and the rebuilt declared class in loopback and external mode, and differs for an
  undeclared exception; Topolith neither replaces the host's meter handler (INV-4) nor records a substitute error.
  Dashboards and alerts that must hold across modes use `exception`.

Implements INV-2, INV-4, INV-6, D-86 through D-89 and D-123.

- AC-EXT-42 (M6.2): A contract call from host code records exactly one `topolith.module.call` observation in internal,
  loopback and external mode, with the same contextual name and the same `module.id`, `caller.module`, `contract`,
  `method` and `exception` values, and `module.mode` naming the mode; a loopback call records none on the provider
  side, and an exposed internal module serving another host's external call records none on the provider host, while
  a contract call its handler makes there is observed.
- AC-EXT-43 (M6.2): `caller.module` names the calling module for a call from a module's class and is `_host` for a
  call from host code; a module's call to its own contract in internal mode records no observation.
- AC-EXT-44 (M6.2): A declared exception, its subclass outside the api packages, a provider throwing
  `ModuleUnavailableException` and an undeclared exception set `exception` to the declared class's simple name, the
  declared ancestor's simple name, `ModuleUnavailableException` and `undeclared`, the same in internal, loopback and
  external mode; a transport failure (an R-EXT-10 fault in loopback mode, a refused connection in external mode) sets
  `ModuleUnavailableException`; and the observation's error is the exception the caller receives.
- AC-EXT-45 (M6.2): In loopback and external mode Spring's `http.client.requests` observation is a child of the
  `topolith.module.call` observation. It stays a child when the exchange runs on another
  thread, with a real Spring Cloud CircuitBreaker (Resilience4j) and with a decorator that hops threads; without an
  `ObservationRegistry` bean nothing is captured; a `none` or internal module gets no handoff.
- AC-EXT-46 (M6.2): Without an `ObservationRegistry` bean a contract call succeeds and nothing is observed; a `none`
  module gets no advice, no client instrumentation and no bean; a `final` handler provider fails startup with TL0029
  with neither a transaction manager nor an `ObservationRegistry` bean, and with a registry while
  `management.observations.enable.topolith=false`.
- AC-EXT-47 (M6.2): A `ModuleCallObservationConvention` bean renames the observation's keys or adds one, in every
  mode; two such beans fail startup with TL0033.
- AC-EXT-79 (M8.7): With Micrometer's meter handler, a provider throwing a declared exception's subclass outside the
  api packages tags the `topolith.module.call` timer with `exception` set to the declared class in internal, loopback
  and external mode, while `error` is the subclass's simple name in internal mode and the declared class's in loopback
  and external mode; the observability docs page states that `exception` is the key to use across modes.

### R-EXT-12 — Context propagation

A loopback or external contract call carries the caller's MDC values for the keys listed in `topolith.context.mdc-keys`
(a list, empty by default) and the caller's tenant, and the provider's module route makes them current for the call
(D-90, D-91). An internal call needs nothing: its provider runs on the caller's thread.

- **Caller.** On the calling thread, for each listed key with an MDC value, the group's client sends the header
  `Topolith-Mdc-<key>`, and with a `TenantContext` bean whose `current()` returns a tenant it sends `Topolith-Tenant`.
  A value is UTF-8, percent-encoded except for RFC 3986 unreserved characters; a key or tenant with no value sends no
  header. An encoded value over 1024 characters, or an MDC value or tenant with an ISO control character (CR and LF
  included), is not sent: an MDC value logs TL0035 naming the key, never the value, and a tenant fails the call before
  it is sent with a `TopolithException` carrying TL0038 (so R-EXT-11's `exception` is `undeclared`). A provider that
  receives a tenant therefore has it or refuses the call (see Provider), and a caller whose tenant cannot be sent
  never calls it (D-98, D-99).
- **Provider.** On a handler of an internal or loopback module's route, Topolith reads the headers of its own listed
  keys only (header names match case-insensitively; the value is stored under the listed spelling) and
  `Topolith-Tenant`. The interceptor is registered at the highest precedence among those registered through
  `WebMvcConfigurer`/`InterceptorRegistry`, so the values are current from the first of those on, a host's own included
  (a `MappedInterceptor` bean runs before the registry's interceptors). Before the handler runs it puts each sent MDC value and removes every listed key the request does
  not carry, even one a server filter put, and with a `TenantContext` bean it calls
  `TenantContext.open(request)` with a `TenantContext.Request` holding the sent tenant, or null when none was sent, so
  the provider sees no tenant then, the request's principal and the id of the module whose route is called. Once the request completes, normally or not, it restores each listed key's previous MDC value
  (removing a key that was absent) and closes the tenant scope, so nothing leaks to the next request on a pooled
  thread. A header that does not decode, is over the limit, or decodes to a value with an ISO control character
  answers 400 without calling the provider, so a client cannot forge a log line through an MDC value. A request
  carrying `Topolith-Tenant` to a host with no `TenantContext` bean answers 500 without calling the provider and logs
  TL0039, and the caller receives R-EXT-04's unspecified exception (`exception` is `undeclared`); MDC headers need no
  bean and are ignorable when no key is listed. The same decisions apply to a forward to a module route. The values are current on the request's dispatch threads only; work the handler hands to another thread
  does not see them, as in internal mode. No other route reads these headers.
- **Trust.** Any client that reaches a module route can send these headers, and a loopback module's routes are on the
  host's own port (D-76), so a module route trusts `Topolith-Tenant` only through `TenantContext.open`'s check: its
  request carries the principal (`HttpServletRequest.getUserPrincipal()`, which under Spring Security is the
  request's `Authentication`, null when anonymous or unauthenticated) and must reject a tenant that principal may not
  act for by throwing. A rejection answers 403 without calling the provider and without sending the exception's
  message, and the caller receives R-EXT-04's `ModuleUnavailableException` with kind `status 403` (D-117, from M8.5;
  the unspecified exception before). Internal calls do not cross a route, so they are never checked.
- **`TenantContext`.** A public `@Incubating` interface in `io.github.rey5137.topolith.spring.boot`: `String current()`
  returns the calling thread's tenant or null, and `TenantContext.Scope open(TenantContext.Request request)` checks the
  request's tenant against its principal and module, then makes it current, or makes no tenant current for null, until
  the scope's `close()` restores the previous state. `Request` is a public final nested class that only Topolith
  constructs (plus `Request.of(tenant, principal, moduleId)` for host tests) with accessors `String tenant()`,
  `java.security.Principal principal()` and `String moduleId()` (the module whose route is called, never null), and
  `equals`, `hashCode` and a `toString` that prints the principal's name only; a later field adds an accessor. It uses JDK types only but is not in `topolith-core`: only
  the HTTP path uses it, and business code needs no Topolith type (INV-1). With no bean no tenant crosses; two beans
  fail startup with TL0034.
- **Parity.** For each listed key and for the tenant the provider observes the caller's value in every mode, and none
  when the caller has none, whatever the provider's server thread held (INV-6). Unlisted MDC keys reach the provider in
  internal mode only. A stated exception: an MDC value or tenant with a control character is refused or dropped on the
  caller and answers 400 on the provider, whereas an internal provider sees the raw value (D-99).
- **Validation.** A listed key that is not a run of letters, digits, `.`, `_` and `-` starting with a letter or digit,
  two keys equal ignoring case, more than 16 keys, or a listed key without SLF4J on the classpath fail startup with
  TL0034 naming the key. The list is read once at startup; a refresh does not change it.
- **Trace context.** Trace and baggage propagation are Micrometer Tracing's and Spring's (W3C headers) and are not
  specified here; a host using Micrometer Tracing may carry a value as a baggage remote field instead of an MDC key.
- **Activation.** The client side exists only on external and loopback groups, and only when a key is listed or a
  `TenantContext` bean exists. The provider side is installed on every host that exposes an internal module or serves
  a loopback one, whether or not a key is listed or a `TenantContext` bean exists, so that a tenant is never ignored
  (D-98); it costs one pass over the request's header names on a module route and nothing elsewhere. A host that
  exposes no module, and a `none` module, get nothing (INV-2).

- **Context carriers (D-122).** A host bean of the public `@Incubating` interface `ContextCarrier`
  (`io.github.rey5137.topolith.spring.boot`; host beans only, as `TenantContext`, so INV-1 holds) carries one more
  string value: `String name()`, `String current()` (the calling thread's value, or null for none) and
  `ContextCarrier.Scope open(String value)` (makes the value current until the scope is closed; null makes none
  current). The caller sends `Topolith-Context-<name>` under the MDC rules above (encoding, 1024-character limit,
  control characters), and an oversize value or one with a control character is not sent and logs TL0035 naming the
  carrier, never the value. The provider opens every carrier for each module-route request, with the sent value or
  null, before the handler, and closes it when the request completes, as for MDC keys. A name follows the MDC key
  pattern; an invalid name, two carriers with names equal ignoring case, or more than 16 MDC keys and carriers together
  fail startup with TL0034. A carrier adds the client side only to external and loopback groups and nothing for a
  `none` module (INV-2). Values that a Micrometer `ThreadLocalAccessor` holds are not carried; W3C baggage with a
  tracer is Micrometer Tracing's (`management.tracing.baggage.remote-fields`), which the docs show.

Implements INV-1, INV-2, INV-4, INV-6, INV-7, D-90, D-91 and D-122.

- AC-EXT-48 (M6.3): A loopback and an external call carry each listed key's MDC value, a non-ASCII value included, and
  the tenant to the provider, which sees the values the provider sees in internal mode; a key with no caller value
  sends no header and the provider sees no value for it, even when a server filter put one in MDC, and a caller with
  no tenant leaves the provider with none.
- AC-EXT-49 (M6.3): After a module route's request completes, normally or by exception, the provider thread's listed
  MDC keys and tenant are back to their previous values, so the next request on that thread sees none of the call's.
- AC-EXT-50 (M6.3): A route outside every module ignores `Topolith-Mdc-*` and `Topolith-Tenant`, a module route ignores
  the header of an unlisted key, and a header that does not decode or is over the limit answers 400 without calling
  the provider; a header that decodes to a value with an ISO control character (CR, LF, tab) in a listed key or in the
  tenant answers 400 too (D-99), including on a forward from a route outside every module to a module route.
- AC-EXT-51 (M6.3): An MDC value over the limit or with a control character is not sent and logs TL0035 naming the
  key but not the value; a tenant over the limit or with a control character fails the call with a
  `TopolithException` carrying TL0038 without sending it, and R-EXT-11's `exception` is `undeclared`. A request
  carrying `Topolith-Tenant` to a host with no `TenantContext` bean answers 500 with TL0039 logged and without calling
  the provider, in loopback and external mode, the caller receives the unspecified exception, and a request with no
  tenant header still passes on such a host (D-98).
- AC-EXT-52 (M6.3): An invalid key, two keys equal ignoring case, more than 16 keys, a listed key without SLF4J on the
  classpath, and two `TenantContext` beans fail startup with TL0034; with no key listed and no `TenantContext` bean,
  and for a `none` module, the client side installs nothing; the provider side is installed only on a host that
  exposes a module, and a host that exposes none, or has only `none` modules, installs none (INV-2). A host
  interceptor registered through `InterceptorRegistry` ahead of the default order sees the sent MDC and tenant, because Topolith's interceptor has the highest
  precedence.
- AC-EXT-58 (M6.3): `TenantContext.open` receives a `Request` whose `principal()` is the `Authentication` under Spring
  Security and null for an anonymous request, and whose `moduleId()` is the module whose route is called; when it rejects the tenant, the module route answers 403 without calling the
  provider or sending the exception's message, and the loopback or external caller receives an exception that is
  neither declared nor `ModuleUnavailableException` (until M8.5; AC-EXT-74 then replaces this clause, D-117).
- AC-EXT-77 (M8.8): With a `ContextCarrier` bean, a loopback and an external call carry its `current()` value as
  `Topolith-Context-<name>` and the provider's handler sees it through the carrier, which is closed after the request
  so a pooled thread keeps nothing; a request without the header opens the carrier with null; an internal call needs no
  header and sees the caller's value.
- AC-EXT-78 (M8.8): An invalid carrier name, two names equal ignoring case, or more than 16 MDC keys and carriers
  together fail startup with TL0034; an oversize value is not sent and logs TL0035 naming the carrier only; a `none`
  module, and a host with no carrier, install no carrier hook.

### R-EXT-13 — Outgoing authentication

Each external or loopback module's client authenticates its calls through at most one outgoing-auth hook, chosen by
`topolith.modules.<id>.auth.type` or supplied by the host (D-92 through D-97).

| `auth.type` | Hook |
|---|---|
| `relay` (default) | Sends the bearer token of the calling thread's `Authentication` |
| `client-credentials` | Spring Security's `OAuth2ClientHttpRequestInterceptor` for the registration `auth.registration-id` |
| `none` | No hook |

- **Relay.** When `SecurityContextHolder`'s `Authentication` on the calling thread is an
  `AbstractOAuth2TokenAuthenticationToken` (a `JwtAuthenticationToken` or `BearerTokenAuthentication`), the call
  sends `Authorization: Bearer <token value>`. With no `Authentication`, or any other kind, it sends no `Authorization`
  and nothing fails. Without Spring Security's resource-server classes on the classpath there is no token to relay and
  no hook is installed. With `spring-security-oauth2-client` on the classpath, an `OAuth2AuthenticationToken` (a
  session login) relays the access token of its `OAuth2AuthorizedClient`, resolved for its registration id and name
  through an `OAuth2AuthorizedClientManager` Topolith builds from the host's `ClientRegistrationRepository` and
  `OAuth2AuthorizedClientRepository` (the repository the login stored the client in) with Spring Security's
  authorization-code and refresh-token providers, so an expired token is refreshed; a host manager bean is not used,
  as for `client-credentials`. As for `client-credentials`, it needs exactly one `ClientRegistrationRepository` and one
  `OAuth2AuthorizedClientRepository` bean: with none of either the host has no session login and session relay is
  off; with two or more of either, a host with a `relay` module fails startup with TL0047 (new, M8.8) naming the beans
  (INV-4). That manager needs the current servlet request, so a call with no current request (on another thread,
  even with the `SecurityContext` propagated) sends no `Authorization` and nothing fails, which is a documented gap.
  When no client resolves it sends no `Authorization` and nothing fails (D-121). The relay hook is then installed with
  either classpath.
- **Unauthenticated relay warning (D-121).** On a host with Spring Security, a request to an internal or loopback
  module's route that carries `Authorization: Bearer` but reaches the handler with no `Authentication`, an
  unauthenticated one or an `AnonymousAuthenticationToken` logs warning TL0046 (new, M8.8) once per module, naming the
  module and the fix (configure the host as a resource server for the caller's tokens), never the token. The call
  still runs.
- **Client credentials.** Topolith always builds the hook's own
  `AuthorizedClientServiceOAuth2AuthorizedClientManager`, with a client-credentials provider only, from the context's
  single `ClientRegistrationRepository` and single `OAuth2AuthorizedClientService` beans; it never uses a host
  `OAuth2AuthorizedClientManager` bean, and a host that wants its own manager installs Spring's interceptor over it
  as a custom hook. The interceptor authorizes the registration `auth.registration-id` for a fixed principal named
  `topolith:<registration id>`, never the calling thread's, so one token per registration is obtained and cached
  whoever calls. Its token request uses the host's single client-credentials `OAuth2AccessTokenResponseClient` bean
  when there is one, and otherwise the connect and read timeouts the module's client currently uses, never its SSL
  bundle or redirect settings; a token request failing on I/O or answered 5xx or 429 raises
  `ModuleUnavailableException` with D-43's kind, while an OAuth2 4xx error such as `invalid_client` or an empty answer
  stays unspecified (D-97). A
  provider response whose OAuth2 error is `invalid_token` (a 401 naming no other error counts), or a token request
  ending in `invalid_grant`, removes the token; the failed call is not retried, and the next call obtains a new one
  (D-96). A host's own wrapper around an I/O failure is not unwrapped (R-EXT-04).
- **Custom hook.** `ModuleClients.authenticate(<id>, <interceptor>)` returns a group configurer, declared as a bean,
  that installs a Spring `ClientHttpRequestInterceptor` as the module's hook instead of `auth.type`'s. Like
  `ModuleClients.configure` it accepts an external or loopback module only (D-83); its interceptor needs no Spring
  Security. Every hook runs after the interceptors an R-EXT-06 customizer adds. The hook's interceptor may run on
  another thread (a circuit breaker's), so a custom hook reads nothing thread-bound (`SecurityContextHolder`, MDC, a
  tenant holder); a value to relay comes from the request.
- **Order.** Topolith installs the hooks in a group configurer that runs after every other configurer: after every
  configurer with an explicit order, after Topolith's own, and after every `ModuleClients.configure` customizer,
  which runs at `Ordered.LOWEST_PRECEDENCE - 100` wherever the host declares it. A plain host
  `RestClientHttpServiceGroupConfigurer` at the lowest precedence ties with the hooks configurer and keeps bean
  registration order, so it can still add an interceptor behind the hook; the call-time check below is the only
  guard against that interceptor overwriting the hook's `Authorization`.
- **One hook per module.** Two `ModuleClients.authenticate` beans for one module, or one together with an explicit
  `auth.type`, fail startup with TL0036 naming the module. A request that reaches the hook already carrying an
  `Authorization` header (a customizer's) and whose `Authorization` the hook changes fails before it is sent with a
  `TopolithException` carrying TL0036 (R-EXT-11's `exception` is `undeclared`), so two credential sources never pick
  a winner silently (INV-4). A default `Authorization` header a customizer sets
  on a module that has any hook, relay included, fails startup with TL0036 naming the module, because the hook
  would collide with it whenever a caller holds a token; an `Authorization` an interceptor sets is only checked at
  call time. The call-time failure is thrown inside the circuit breaker, so a group fallback of the host can swallow
  it and return its fallback value; the startup check moves the common case (a default header) out of that path.
- **Validation.** An `auth.*` key for a module that is neither external nor loopback, an unknown `auth.*` key or
  `auth.type` value, an `auth.registration-id` without `client-credentials`, or a `ModuleClients.authenticate` naming
  such a module fail with TL0003 naming the key or module. `client-credentials` without Spring Security's OAuth2
  client classes, without `auth.registration-id`, with a registration the `ClientRegistrationRepository` does not hold,
  without exactly one `ClientRegistrationRepository` and one `OAuth2AuthorizedClientService` bean to build its
  manager from, or with more than one client-credentials `OAuth2AccessTokenResponseClient` bean, fails startup with
  TL0037 naming the module. The keys are restart-only: a refresh changing one logs
  TL0010 naming the module and the key and marks restart pending, as D-77 does for a loopback module's client settings.
- **Parity.** In internal mode the provider runs on the caller's thread and sees the caller's `SecurityContext`;
  Topolith neither copies nor clears it. With `relay`, in loopback and external mode the provider sees the
  `Authentication` its host's resource-server configuration builds from the relayed token. "The same
  `Authentication`" means the same `getName()` and the same set of authorities, never the same object, type or
  credentials, and parity tests compare those two (D-94). An anonymous caller, or a session login whose authorized
  client does not resolve, relays nothing, and a loopback host with no resource-server configuration does not
  authenticate a relayed token, so in loopback and external mode the provider sees whatever its security chain makes of
  a request without credentials; these are documented gaps that loopback shows before extraction, not startup failures
  (D-95), and the second one logs TL0046 at the first such call (D-121).
  `client-credentials` deliberately relaxes this parity: the provider sees the client's principal in loopback and
  external mode and the caller's in internal mode.
- **Activation.** Only an external or loopback module's client gets a hook; a `none` or internal module has no client
  and no auth interceptor (INV-2). Spring Security stays an optional dependency of `topolith-spring-boot`, used behind
  class checks (INV-7). The relay reads the calling thread's `SecurityContext` at the call and sends a value, so
  nothing assumes the caller and provider share a thread (INV-6); a call made on another thread relays what that
  thread's `SecurityContext` holds, which is also what an internal provider would see there.

Implements INV-2, INV-4, INV-6, INV-7, D-92 through D-97 and D-121.

- AC-EXT-53 (M6.4): With `relay`, a loopback and an external call from a thread whose `Authentication` is a
  `JwtAuthenticationToken` send its token as `Authorization: Bearer`, and the provider sees an `Authentication` with
  the caller's name and authorities, as an internal provider does; with no `Authentication` or one without a token the
  call sends no `Authorization` and succeeds (from M8.8 a session login sends its authorized client's token,
  AC-EXT-75).
- AC-EXT-75 (M8.8): With `relay` and `spring-security-oauth2-client`, a loopback and an external call from a thread
  whose `Authentication` is an `OAuth2AuthenticationToken` send its authorized client's access token, refreshed when
  expired, also with a host `OAuth2AuthorizedClientManager` bean present; with no authorized client, and from an
  `@Async` thread with the `SecurityContext` propagated but no current request, the call sends no `Authorization` and
  succeeds; two `OAuth2AuthorizedClientRepository` beans (or two `ClientRegistrationRepository` beans) with a `relay`
  module fail startup with TL0047 (new, M8.8); with no `ClientRegistrationRepository` session relay is off.
- AC-EXT-76 (M8.8): A relayed bearer token that a loopback host without resource-server configuration leaves
  unauthenticated logs TL0046 (new, M8.8) once per module, naming the module and never the token, and the call runs; an
  authenticated relayed call and a call without `Authorization` log nothing; a host without Spring Security, and a
  host that exposes no internal module and serves no loopback one (only `none` or `external` modules), installs no
  check (INV-2).
- AC-EXT-54 (M6.4): An unset `auth.type` relays, `none` sends no `Authorization`, and `client-credentials` sends the
  access token Spring Security's OAuth2 client obtains for `auth.registration-id`, also from a thread outside any
  servlet request and with a host `OAuth2AuthorizedClientManager` bean present; calls by two different users obtain
  one token, stored for the principal `topolith:<registration id>`.
- AC-EXT-59 (M6.4): With `client-credentials`, a provider answer of 401 whose `WWW-Authenticate` names
  `invalid_token`, or no error, fails that call without retrying it and removes the token, so the next call sends a
  new one; a 403 naming `insufficient_scope` keeps the token.
- AC-EXT-60 (M6.4): A `client-credentials` token request uses the host's single client-credentials
  `OAuth2AccessTokenResponseClient` bean when there is one; otherwise it has the module's read timeout, also after a
  refresh lowers it, and a token request that times out fails the call with `ModuleUnavailableException` (timeout).
- AC-EXT-61 (M6 gate): A `client-credentials` token request answered 503 or 429, or ending in an I/O failure, fails
  the call with `ModuleUnavailableException` (connection failure), also when a 5xx is under a circuit breaker's
  `NoFallbackAvailableException`, and also when the failure is nested in another exception inside Spring Security's;
  one answered 400 `invalid_client`, or an OAuth2 failure with no cause, reaches the caller as the OAuth2 exception;
  a host's own wrapper around a `ResourceAccessException` reaches the caller as thrown. A token-request factory an
  R-EXT-08 refresh replaces is closed when it is `AutoCloseable` or `DisposableBean`.
- AC-EXT-55 (M6.4): `ModuleClients.authenticate(<id>, <interceptor>)` installs the interceptor as that module's only
  hook; two hooks for one module, or a hook with an explicit `auth.type`, fail startup with TL0036, a default `Authorization`
  header a customizer sets on a module with any hook (relay included) fails startup with TL0036, and a hook that
  changes an `Authorization` header an interceptor set fails the call with a `TopolithException` carrying TL0036
  without sending it.
- AC-EXT-56 (M6.4): An `auth.*` key for an internal or `none` module, an unknown key or value, an
  `auth.registration-id` without `client-credentials` and a `ModuleClients.authenticate` for an internal module fail
  with TL0003; `client-credentials` without the OAuth2 client classes, without a registration id, with an unknown one,
  without a `ClientRegistrationRepository` or `OAuth2AuthorizedClientService` bean, with two of either, or with two
  client-credentials `OAuth2AccessTokenResponseClient` beans fails with TL0037; a refresh changing an `auth.*` key logs TL0010 and marks restart pending.
- AC-EXT-57 (M6.4): An internal call's provider sees the caller's `SecurityContext` unchanged; an internal or `none`
  module gets no auth interceptor, and a host without Spring Security on the classpath starts and its loopback and
  external calls send no `Authorization`.

### R-EXT-14 — Per-group retry

Each external or loopback module's HTTP service group can retry transient provider transport failures of its idempotent
contract calls, configured under `topolith.modules.<id>.remote.retry` and wired by `topolith-spring-cloud`'s resilience
auto-configuration (which is not gated on `EnvironmentChangeEvent` or `spring-cloud-context`) over Spring Framework 7's
`org.springframework.core.retry` (D-100, D-101, D-103 through D-105). Retry is off by default (`max-attempts=1`).

| Key | Type and default | Meaning |
|---|---|---|
| `max-attempts` | int in `[1, 10]`, `1` | Total attempts for an idempotent call (`1` means no retry) |
| `initial-backoff` | duration `>= 0`, `100ms` | Wait before the first retry |
| `max-backoff` | duration `>= initial-backoff`, `max(1s, initial-backoff)` | Upper bound on the wait between attempts |
| `multiplier` | double `>= 1.0` (finite), `2.0` | Exponential backoff factor applied between attempts |
| `safe-methods-idempotent` | boolean, `false` | Also treats `GET`, `HEAD`, `OPTIONS` and `TRACE` exchanges as idempotent for retry |

- **Idempotency gate.** Retry applies per HTTP exchange (abstract contract method) invocation; a `default` method on a
  contract interface runs locally on the client proxy and is not itself an exchange (each abstract contract method it
  invokes is checked on its own exchange). An exchange is eligible for retry only when `max-attempts > 1` and either:
  (a) the module's effective descriptor (`apis/<id>.properties` for an external module, `modules/<id>.properties` for
  a loopback one, never a union) lists `<contract>#<method>` in `idempotent` (R-DESC-05, D-100); or
  (b) `remote.retry.safe-methods-idempotent=true` and the method's resolved HTTP method on the caller's exchange
  interface — the contract interface itself, or the separate interface mapped by
  `topolith.modules.<id>.remote.http-interface.<contract FQN>` (D-49), with a non-empty method-level `@HttpExchange`
  method winning over a type-level one — is `GET`, `HEAD`, `OPTIONS` or `TRACE` (RFC 9110 §9.2.1 safe methods,
  case-insensitive). `PUT`, `DELETE`, `POST`, `PATCH` and any other HTTP method are never retried unless the contract
  method is in `idempotent`. The wait before retry $k \ge 1$ is
  $\min(\text{max-backoff}, \text{initial-backoff} \times \text{multiplier}^{k-1})$. A non-idempotent call makes at
  most one attempt, whatever `max-attempts` is set to.
- **Retryable outcomes (allowlist).** Only four transient provider transport and availability outcomes of the HTTP
  exchange are retried: (1) a connection failure (`ResourceAccessException` other than a timeout, including R-EXT-10's
  `connection-failure` fault); (2) a connect or read timeout (`ResourceAccessException` timeout, including R-EXT-10's
  `timeout` fault or latency at or above the read timeout); (3) a circuit-breaker time-limiter
  `java.util.concurrent.TimeoutException` (defensive only: it cannot occur while D-106 keeps Spring Cloud's global
  breaker off for a resilient module; Topolith's per-group breaker has no time limiter, R-EXT-15); and (4) an HTTP 502, 503 or 504 response without a Topolith
  `urn:topolith:problem:contract-exception` body (including R-EXT-10's `unavailable` fault). After a read or
  time-limiter timeout the provider may have run, which is why only idempotent calls are retried.
- **Non-retryable outcomes.** Every other outcome is not retried: any Topolith
  `urn:topolith:problem:contract-exception` response (a declared contract exception, a provider's own
  `ModuleUnavailableException` problem from a downstream call, or a problem class the caller cannot rebuild); any 4xx
  response (including 401 with `client-credentials`, which still evicts the token without retrying per R-EXT-13, D-96,
  and 429); a 5xx response without a Topolith problem other than 502, 503 or 504 (such as 500); zero LoadBalancer
  instances (`no instance`); a call the circuit breaker rejects because the circuit is open (`CallNotPermittedException`
  with Resilience4j, kind `circuit open`); a failed client-credentials token request (`OAuth2AuthorizationException`,
  D-97); a call-time `TopolithException` (`TL0036`, `TL0038`); or any other exception (such as a host interceptor's or
  conversion exception). When retries are exhausted, or on a non-retryable failure, `ContractCallTranslator` translates
  the final failure as in R-EXT-04.
- **Classification through a circuit breaker.** Retry, the circuit breaker's record/ignore decision (R-EXT-15) and
  `ContractCallTranslator` share one classification (D-105). When an attempt throws Spring Cloud CircuitBreaker's
  `NoFallbackAvailableException` (found by class name), retry classifies its direct cause, as D-51 translation does: a
  retryable cause is retried, a `java.util.concurrent.TimeoutException` cause is a timeout, and a
  `CallNotPermittedException` thrown or as that cause stops retry at once with kind `circuit open`. No other wrapper,
  including a host's own, is unwrapped, and it is not retried.
- **Parity and call context.** An `internal` module has no transport and never retries. A `loopback` module's group
  client retries in the same way as an `external` module's; each retry attempt draws the next entry from the module's
  R-EXT-10 fault sequence. One contract call still records a single `topolith.module.call` observation on the caller's
  side (R-EXT-11) spanning all attempts, with `exception` set from the call's final outcome (`none` if a retry
  succeeds) and each HTTP attempt's `http.client.requests` observation as its child. Listed MDC headers,
  `Topolith-Tenant` (R-EXT-12) and the relayed bearer token (R-EXT-13) captured on the calling thread are sent on every
  attempt (D-103).
- **Validation, activation and refresh.** A `remote.retry.*` key on an `internal`, `none` or unknown module, an unknown
  `remote.retry.*` key, an invalid value (`max-attempts` outside `[1, 10]`, a negative or unparsable duration, an
  explicit `max-backoff` below `initial-backoff`, `multiplier` below `1.0` or non-finite, `safe-methods-idempotent` not
  a boolean), or setting `remote.retry.max-attempts > 1` while Spring Cloud's global
  `spring.cloud.circuitbreaker.http-services.enabled=true` is also set fails startup with TL0003 naming the key. Spring
  Cloud applies its global breaker when that key is unset, so Topolith defaults the key to `false` (a lowest-priority
  property a host's own value overrides) while a module has `remote.retry.max-attempts > 1` or
  `remote.circuit-breaker.enabled=true` (D-106). Tuning
  keys (`initial-backoff`, `max-backoff`, `multiplier`, `safe-methods-idempotent`) are allowed when `max-attempts` is
  unset or `1`: their values are validated at startup and refresh, and they are inactive while `max-attempts=1`.
  Setting `remote.retry.max-attempts > 1` without `topolith-spring-cloud`'s resilience configuration on the classpath and active (not excluded)
  fails startup with TL0041 naming the module and key. A refresh that turns retry on without it active fails with TL0011
  (a TL0041 condition) and a failed `RESILIENCE` status. A `none` or `internal` module registers no
  retry bean, configurer or decorator (INV-2). On refresh (`EnvironmentChangeEvent`), an `external` module validates
  and swaps only its active retry policy atomically (leaving its running circuit breaker and breaker state untouched)
  and logs TL0011 on invalid settings while keeping the running policy (R-EXT-08, D-104). A call captures its policy
  once when it starts (D-107). Turning retry on is restart-only (TL0010, restart pending) while Spring Cloud's global
  breaker is applied, which is judged by its configurer bean being in the context, not by the key (D-106). A `loopback` module's
  `remote.retry.*` keys are restart-only (TL0010, D-77).

Implements INV-2, INV-4, INV-6, INV-7 and D-100 through D-108.

- AC-EXT-62 (M7.3): By default (`remote.retry.safe-methods-idempotent` unset or `false`), a contract method that is not
  declared `@Idempotent` (including a `@GetExchange`, `@PutExchange`, `@DeleteExchange` or `@PostExchange` method
  without `@Idempotent`) is never retried: with `remote.retry.max-attempts` above `1`, a connection failure, timeout or
  503 makes only one attempt and raises `ModuleUnavailableException`, in both loopback and external mode.
- AC-EXT-69 (M7.3): With `remote.retry.safe-methods-idempotent=true` and `remote.retry.max-attempts` above `1`, an
  unannotated contract method whose resolved HTTP method on the caller's exchange interface (the contract itself, or a
  separate `remote.http-interface`) is `GET`, `HEAD`, `OPTIONS` or `TRACE` is retried on retryable outcomes as an
  `@Idempotent` method is, whereas an unannotated `POST`, `PUT`, `DELETE` or `PATCH` method is still never retried; the
  descriptor's `idempotent` set is unchanged.
- AC-EXT-63 (M7.3): An `@Idempotent` contract method with `remote.retry.max-attempts` above `1` is retried up to
  `max-attempts` total attempts with exponential backoff (`initial-backoff`, `max-backoff` defaulting to
  `max(1s, initial-backoff)`, `multiplier`) on a connection failure, a timeout (including an R-EXT-10 `timeout` or
  latency fault, where the provider ran on the timed-out attempt and runs again on the retry), and a 502, 503 or 504
  without a Topolith problem (including R-EXT-10 `connection-failure` and `unavailable` faults), returning the result
  when a retry succeeds and raising `ModuleUnavailableException` when all attempts fail; with `max-attempts` unset or
  `1` (even when valid backoff keys are present) it makes one attempt.
- AC-EXT-64 (M7.3): An idempotent contract call is never retried on a Topolith contract-exception problem response (a
  declared exception, a provider's own `ModuleUnavailableException` problem, or an unrebuildable class), a 4xx response
  (including 401 with `client-credentials`, which evicts the token once without retrying, and 429), a 5xx response
  without a Topolith problem other than 502, 503 or 504 (such as 500), zero LoadBalancer instances (`no instance`), a
  failed client-credentials token request (`OAuth2AuthorizationException`), a `TopolithException` (`TL0036`, `TL0038`),
  or an open circuit (`circuit open`).

### R-EXT-15 — Per-group circuit breaker

Each external or loopback module's HTTP service group can wrap its exchange attempts in a Spring Cloud CircuitBreaker
named `<id>`, configured under `topolith.modules.<id>.remote.circuit-breaker` and wired by `topolith-spring-cloud`
(D-51, D-102 through D-105). The circuit breaker is off by default (`enabled=false`). Each key has the meaning given
here whatever the implementation; settings the keys do not expose (half-open permits, slow-call thresholds, window
type) are fixed at the supported implementation's documented defaults and are not taken from a host default
configuration (Topolith builds `<id>`'s configuration from a fresh builder so a host `configureDefault` never leaks in).
In 0.1 the only supported `CircuitBreakerFactory` implementation is Resilience4j (`Resilience4JCircuitBreakerFactory`),
wired through a package-private adapter in `topolith-spring-cloud` with no public SPI; another implementation is added
later as an internal adapter with no key, diagnostic code or rule change (D-105).

| Key | Type and default | Meaning |
|---|---|---|
| `enabled` | boolean, `false` | Wraps module `<id>`'s group exchanges in a Spring Cloud `CircuitBreaker` named `<id>` |
| `failure-rate-threshold` | float in `(0, 100]`, `50` | Failure percentage at or above which the circuit opens |
| `sliding-window-size` | int `>= 1`, `100` | Number of calls in the count-based sliding window |
| `minimum-number-of-calls` | int in `[1, sliding-window-size]`, `10` (the window size when that is smaller) | Minimum calls recorded before the failure rate can open the circuit |
| `wait-duration-in-open-state` | duration of at least `1ms`, `60s` | How long the circuit stays open before entering half-open |

- **What trips the breaker and D-51 translation.** Topolith classifies each attempt's outcome itself, identically in
  every breaker implementation. A breaker failure is recorded only for a transport or availability failure of the
  provider hop: a connection failure, a connect or read timeout (a time-limiter timeout is defensive only, as in
  R-EXT-14), a 5xx response without a Topolith
  `urn:topolith:problem:contract-exception` body (502, 503, 504 and any other 5xx such as 500 without a Topolith
  problem), or zero LoadBalancer instances. A successful response records a success. Every other outcome is ignored,
  counting neither as a failure nor as a success: any Topolith problem response (a declared exception, a provider's own
  `ModuleUnavailableException`, or a class the caller cannot rebuild), any 4xx response (including 401 `invalid_token`
  eviction and 429), a failed client-credentials token request (D-97), a `TopolithException` (TL0036, TL0038) and any
  other exception. The implementation's default classification never applies. Each attempt runs on the calling thread
  with no time limiter and no bulkhead; per-attempt time is governed by the group's R-EXT-03 connect and read timeouts
  only. A declared exception survives the breaker's no-fallback wrapper through `ContractCallTranslator` as its
  declared type (R-EXT-04, D-51). When the circuit is open (or half-open permits are exhausted), the breaker rejects
  the attempt (`CallNotPermittedException` with Resilience4j), which `ContractCallTranslator` translates to
  `ModuleUnavailableException` with kind `circuit open`.
- **No fallback.** Topolith installs no circuit-breaker fallback (INV-4). A host `@HttpServiceFallback` bean targeting
  a Topolith module group fails startup with TL0003 so a failure is never hidden by a fallback value.
- **Order with retry.** Retry (R-EXT-14) runs outside the circuit breaker: each attempt of an idempotent call enters
  the circuit breaker, so every failed attempt counts toward `minimum-number-of-calls` and `sliding-window-size`, and as
  soon as the circuit opens, the breaker's rejection stops retry immediately without sleeping through remaining
  attempts and the caller receives `ModuleUnavailableException` with kind `circuit open`.
- **Validation, ownership, activation and refresh.** A `remote.circuit-breaker.*` key on an `internal`, `none` or
  unknown module, an unknown key, or an invalid value (`enabled` not a boolean, `failure-rate-threshold` outside
  `(0, 100]` or non-finite, `sliding-window-size` or `minimum-number-of-calls` below `1`, `minimum-number-of-calls`
  above the effective `sliding-window-size`, `wait-duration-in-open-state` below `1ms`) fails startup with TL0003
  naming the key. Threshold keys are allowed when `enabled` is unset or `false`: their values are validated at startup
  and refresh, and they are inactive while `enabled=false`. When `remote.circuit-breaker.enabled=true`, Topolith alone
  owns breaker `<id>`: another source configuring `<id>` (a Spring Cloud `Customizer` or `configure` callback for
  `<id>`, `resilience4j.circuitbreaker` or `resilience4j.timelimiter` `instances.<id>` or `configs.<id>`, or
  `spring.cloud.circuitbreaker.resilience4j.disable-time-limiter-map.<id>`), a breaker named `<id>` already in the
  `CircuitBreakerRegistry`, an active Resilience4j bulkhead provider on the factory (fix:
  `spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled=false`), or
  `spring.cloud.circuitbreaker.http-services.enabled=true` fails startup with TL0003 naming the module and the
  conflicting source. Setting `remote.circuit-breaker.enabled=true` requires `topolith-spring-cloud`'s resilience
  configuration and exactly one `CircuitBreakerFactory` bean of a supported implementation (0.1:
  `Resilience4JCircuitBreakerFactory`); zero, two or more, or an unsupported `CircuitBreakerFactory` bean fails startup
  with TL0041 naming the module and the beans found (never `@Primary` or `getIfUnique`, INV-4). The adapter to the
  supported implementation is internal; a later supported implementation adds another internal adapter and the key
  `topolith.resilience.circuit-breaker.factory=<bean name>` to select among several supported factories, still
  failing with TL0041 without it, and no public adapter SPI is offered until two implementations exist (D-124,
  decision only in 0.1). A `none` or `internal`
  module registers no circuit-breaker bean, configurer or decorator (INV-2). On refresh (`EnvironmentChangeEvent`), an
  `external` module rebuilds its circuit breaker only when `remote.circuit-breaker.*` changes — resetting the rebuilt
  breaker to closed with an empty window under the new thresholds, or removing it when `enabled` becomes `false`, while
  changing only `remote.retry.*`, URL, service id or timeouts leaves the running breaker and its open/closed state
  untouched — and logs TL0011 on invalid settings or a missing/unsupported `CircuitBreakerFactory` while keeping the
  running breaker (R-EXT-08, D-104). A call captures its breaker instance once when it starts, so a call in flight
  never records into a rebuilt breaker (D-107). Turning the breaker on is restart-only (TL0010, restart pending) while
  Spring Cloud's global breaker is applied (its configurer bean is in the context, D-106). A `loopback` module's `remote.circuit-breaker.*` keys are restart-only (TL0010,
  D-77).

Implements INV-2, INV-4, INV-6, INV-7, D-51, D-102 through D-107 and D-124.

- AC-EXT-65 (M7.3): With `remote.circuit-breaker.enabled=true`, calls to that module's group run on the calling thread
  through a Spring Cloud CircuitBreaker configured with the group's `failure-rate-threshold`, `sliding-window-size`,
  `minimum-number-of-calls` and `wait-duration-in-open-state`: provider-hop failures (connection failure, timeout, 5xx
  without a Topolith problem, zero instances) trip the circuit and an open circuit rejects calls with
  `ModuleUnavailableException` of kind `circuit open` without calling the provider, while a declared contract
  exception, a provider's Topolith problem naming `ModuleUnavailableException` (which still raises
  `ModuleUnavailableException` with the downstream `moduleId()` and kind `unavailable downstream`), a 404 without a
  Topolith problem, a 401 and a `TL0038` call, each repeated past `minimum-number-of-calls`, are ignored and leave the
  circuit closed so another method of the provider still succeeds, and a declared exception survives the breaker as its
  declared type (D-51); with `remote.retry.max-attempts=3` and the breaker enabled, an `@Idempotent` call whose first
  attempt hits a connection failure and whose second attempt succeeds returns the result with one failure and one
  success recorded in the breaker's window, and when a later attempt trips the circuit open, retry stops immediately
  with kind `circuit open`.
- AC-EXT-66 (M7.3): Under retry and the circuit breaker, one contract call records exactly one `topolith.module.call`
  observation (R-EXT-11) whose `exception` tag is `none` when a retry succeeds and the final exception's value when the
  call fails, with each HTTP attempt's `http.client.requests` observation as its child, and every retry attempt carries
  the caller's listed MDC headers, `Topolith-Tenant` (R-EXT-12) and relayed bearer token (R-EXT-13); an `internal`
  module never retries and installs no breaker, and a `none` module registers no retry or circuit-breaker bean,
  configurer or decorator (INV-2).
- AC-EXT-67 (M7.3): Valid retry tuning keys with `max-attempts` unset or `1` and valid breaker threshold keys with
  `circuit-breaker.enabled` unset or `false` start without error and leave retry and the breaker off; a
  `remote.retry.*` or `remote.circuit-breaker.*` key for an `internal`, `none` or unknown module, an unknown key, an
  invalid value (`max-attempts` outside `[1, 10]`, a negative backoff, an explicit `max-backoff` below
  `initial-backoff`, `multiplier` below `1.0`, `safe-methods-idempotent` or `circuit-breaker.enabled` not a boolean,
  `failure-rate-threshold` outside `(0, 100]`, `sliding-window-size` or `minimum-number-of-calls` below `1`,
  `minimum-number-of-calls` above the effective `sliding-window-size`, `wait-duration-in-open-state` below `1ms`),
  `max-attempts` above `1` or `circuit-breaker.enabled=true` together with
  `spring.cloud.circuitbreaker.http-services.enabled=true` or a Spring Cloud HTTP-service breaker configurer bean in
  the context (D-106), another source configuring breaker `<id>`, a breaker named
  `<id>` already in `CircuitBreakerRegistry`, an active Resilience4j bulkhead provider on the factory, or a host
  `@HttpServiceFallback` targeting a module group fails startup with TL0003; `remote.retry.max-attempts` above `1`
  without `topolith-spring-cloud`'s resilience configuration on the classpath and active, or
  `remote.circuit-breaker.enabled=true` without it or
  with zero, two or more, or an unsupported `CircuitBreakerFactory` bean, or one whose Spring Cloud members Topolith
  reads to verify it alone owns the breaker are missing or unreadable (D-105), fails startup with TL0041; the same
  condition on a refresh fails with TL0011 and a failed `RESILIENCE` status.
- AC-EXT-68 (M7.4): On an `EnvironmentChangeEvent` for an `external` module, changing `remote.retry.*` swaps only the
  module's retry policy (turning retry on or off while keeping tuning keys, and leaving an open circuit breaker open),
  and changing `remote.circuit-breaker.*` rebuilds only the module's circuit breaker (resetting it to closed under the
  new thresholds, or turning it off while keeping threshold keys) while calls in flight finish on the previous policy
  and breaker; invalid refreshed retry or circuit-breaker settings (any TL0003 or TL0041 condition except the global-breaker key,
  which Spring Cloud reads only at startup and a refresh never tests, D-106) or a rebuild
  failure leave the running policy and breaker unchanged, log TL0011 and surface the failure on the `topolith` actuator
  endpoint; on a `loopback` module, changing `remote.retry.*` or `remote.circuit-breaker.*` leaves the client
  unchanged, logs TL0010 and marks restart pending until the keys match their running values. A call in flight
  keeps the policy and the breaker instance it started with (D-107). With a `Resilience4JCircuitBreakerFactory`, the
  global key unset and every module's retry and breaker off, Spring Cloud's global breaker is applied, so refreshing
  `max-attempts=3` (or `circuit-breaker.enabled=true`) is not applied: one attempt, no Topolith breaker in the
  registry, TL0010 and restart pending; setting `max-attempts` back to `1` clears it.
