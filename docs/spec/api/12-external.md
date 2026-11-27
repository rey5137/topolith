# 12 — External contracts and clients

This file specifies external HTTP clients, discovery, errors, exposure and endpoint refresh.
It also specifies the internal transaction boundary, loopback calls, the call observation, context propagation and
outgoing authentication.

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
internal module implementing a provided contract fails startup with TL0029, whether or not a transaction manager
exists (D-89); a `final` provider that is not a handler takes an interface proxy.

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
`spring.http.clients.*`) is restart-only and unchecked until the next start (D-50). A rebuild also gives a
client-credentials hook's token request the new timeouts (D-96). Implements INV-4, INV-9, D-11 through D-16, D-20.

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
  `http.client.requests` observation, and in internal mode the provider's own observations, are its children. On the
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

Implements INV-2, INV-4, INV-6 and D-86 through D-89.

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
  `topolith.module.call` observation.
- AC-EXT-46 (M6.2): Without an `ObservationRegistry` bean a contract call succeeds and nothing is observed; a `none`
  module gets no advice, no client instrumentation and no bean; a `final` handler provider fails startup with TL0029
  with neither a transaction manager nor an `ObservationRegistry` bean, and with a registry while
  `management.observations.enable.topolith=false`.
- AC-EXT-47 (M6.2): A `ModuleCallObservationConvention` bean renames the observation's keys or adds one, in every
  mode; two such beans fail startup with TL0033.

### R-EXT-12 — Context propagation

A loopback or external contract call carries the caller's MDC values for the keys listed in `topolith.context.mdc-keys`
(a list, empty by default) and the caller's tenant, and the provider's module route makes them current for the call
(D-90, D-91). An internal call needs nothing: its provider runs on the caller's thread.

- **Caller.** On the calling thread, for each listed key with an MDC value, the group's client sends the header
  `Topolith-Mdc-<key>`, and with a `TenantContext` bean whose `current()` returns a tenant it sends `Topolith-Tenant`.
  A value is UTF-8, percent-encoded except for RFC 3986 unreserved characters; a key or tenant with no value sends no
  header. An encoded value over 1024 characters is not sent: an MDC value logs TL0035 naming the key, never the value,
  and a tenant fails the call before it is sent with a `TopolithException` carrying TL0038 (so R-EXT-11's `exception`
  is `undeclared`), so a provider never runs without the caller's tenant.
- **Provider.** On a handler of an internal or loopback module's route, Topolith reads the headers of its own listed
  keys only (header names match case-insensitively; the value is stored under the listed spelling) and
  `Topolith-Tenant`. Before the handler runs it puts each sent MDC value and removes every listed key the request does
  not carry, even one a server filter put, and with a `TenantContext` bean it calls
  `TenantContext.open(tenant, principal)` with the sent tenant, or null when none was sent, so the provider sees no
  tenant then. Once the request completes, normally or not, it restores each listed key's previous MDC value
  (removing a key that was absent) and closes the tenant scope, so nothing leaks to the next request on a pooled
  thread. A header that does not decode or is over the limit answers 400 without calling the provider. No other route
  reads these headers.
- **Trust.** Any client that reaches a module route can send these headers, and a loopback module's routes are on the
  host's own port (D-76), so a module route trusts `Topolith-Tenant` only through `TenantContext.open`'s check: it
  receives the request's principal (`HttpServletRequest.getUserPrincipal()`, which under Spring Security is the
  request's `Authentication`, null when anonymous or unauthenticated) and must reject a tenant that principal may not
  act for by throwing. A rejection answers 403 without calling the provider and without sending the exception's
  message, and the caller receives R-EXT-04's unspecified exception (`exception` is `undeclared`). Internal calls do
  not cross a route, so they are never checked.
- **`TenantContext`.** A public `@Incubating` interface in `io.github.rey5137.topolith.spring.boot`: `String current()`
  returns the calling thread's tenant or null, and `TenantContext.Scope open(String tenant, java.security.Principal
  principal)` checks the tenant against the principal, then makes it current, or makes no tenant current for null,
  until the scope's `close()` restores the previous state. It uses JDK types only but is not in `topolith-core`: only
  the HTTP path uses it, and business code needs no Topolith type (INV-1). With no bean no tenant crosses; two beans
  fail startup with TL0034.
- **Parity.** For each listed key and for the tenant the provider observes the caller's value in every mode, and none
  when the caller has none, whatever the provider's server thread held (INV-6). Unlisted MDC keys reach the provider in
  internal mode only.
- **Validation.** A listed key that is not a run of letters, digits, `.`, `_` and `-` starting with a letter or digit,
  two keys equal ignoring case, more than 16 keys, or a listed key without SLF4J on the classpath fail startup with
  TL0034 naming the key. The list is read once at startup; a refresh does not change it.
- **Trace context.** Trace and baggage propagation are Micrometer Tracing's and Spring's (W3C headers) and are not
  specified here; a host using Micrometer Tracing may carry a value as a baggage remote field instead of an MDC key.
- **Activation.** The client side exists only on external and loopback groups and the provider side only with an
  internal or loopback module, each only when a key is listed or a `TenantContext` bean exists; a `none` module gets
  neither (INV-2).

Implements INV-1, INV-2, INV-4, INV-6, INV-7, D-90 and D-91.

- AC-EXT-48 (M6.3): A loopback and an external call carry each listed key's MDC value, a non-ASCII value included, and
  the tenant to the provider, which sees the values the provider sees in internal mode; a key with no caller value
  sends no header and the provider sees no value for it, even when a server filter put one in MDC, and a caller with
  no tenant leaves the provider with none.
- AC-EXT-49 (M6.3): After a module route's request completes, normally or by exception, the provider thread's listed
  MDC keys and tenant are back to their previous values, so the next request on that thread sees none of the call's.
- AC-EXT-50 (M6.3): A route outside every module ignores `Topolith-Mdc-*` and `Topolith-Tenant`, a module route ignores
  the header of an unlisted key, and a header that does not decode or is over the limit answers 400 without calling
  the provider.
- AC-EXT-51 (M6.3): An MDC value over the limit is not sent and logs TL0035 naming the key but not the value; a tenant
  over the limit fails the call with a `TopolithException` carrying TL0038 without sending it, and R-EXT-11's
  `exception` is `undeclared`.
- AC-EXT-52 (M6.3): An invalid key, two keys equal ignoring case, more than 16 keys, a listed key without SLF4J on the
  classpath, and two `TenantContext` beans fail startup with TL0034; with no key listed and no `TenantContext` bean,
  and for a `none` module, Topolith installs no propagation interceptor.
- AC-EXT-58 (M6.3): `TenantContext.open` receives the request's principal, the `Authentication` under Spring Security
  and null for an anonymous request; when it rejects the tenant, the module route answers 403 without calling the
  provider or sending the exception's message, and the loopback or external caller receives an exception that is
  neither declared nor `ModuleUnavailableException`.

### R-EXT-13 — Outgoing authentication

Each external or loopback module's client authenticates its calls through at most one outgoing-auth hook, chosen by
`topolith.modules.<id>.auth.type` or supplied by the host (D-92 through D-96).

| `auth.type` | Hook |
|---|---|
| `relay` (default) | Sends the bearer token of the calling thread's `Authentication` |
| `client-credentials` | Spring Security's `OAuth2ClientHttpRequestInterceptor` for the registration `auth.registration-id` |
| `none` | No hook |

- **Relay.** When `SecurityContextHolder`'s `Authentication` on the calling thread is an
  `AbstractOAuth2TokenAuthenticationToken` (a `JwtAuthenticationToken` or `BearerTokenAuthentication`), the call
  sends `Authorization: Bearer <token value>`. With no `Authentication`, or any other kind, it sends no `Authorization`
  and nothing fails. Without Spring Security's resource-server classes on the classpath there is no token to relay and
  no hook is installed.
- **Client credentials.** Topolith always builds the hook's own
  `AuthorizedClientServiceOAuth2AuthorizedClientManager`, with a client-credentials provider only, from the context's
  single `ClientRegistrationRepository` and single `OAuth2AuthorizedClientService` beans; it never uses a host
  `OAuth2AuthorizedClientManager` bean, and a host that wants its own manager installs Spring's interceptor over it
  as a custom hook. The interceptor authorizes the registration `auth.registration-id` for a fixed principal named
  `topolith:<registration id>`, never the calling thread's, so one token per registration is obtained and cached
  whoever calls. Its token request uses the host's single client-credentials `OAuth2AccessTokenResponseClient` bean
  when there is one, and otherwise the connect and read timeouts the module's client currently uses, never its SSL
  bundle or redirect settings; a token request failing on I/O raises `ModuleUnavailableException` with D-55's kind. A
  provider response whose OAuth2 error is `invalid_token` (a 401 naming no other error counts), or a token request
  ending in `invalid_grant`, removes the token; the failed call is not retried, and the next call obtains a new one
  (D-96).
- **Custom hook.** `ModuleClients.authenticate(<id>, <interceptor>)` returns a group configurer, declared as a bean,
  that installs a Spring `ClientHttpRequestInterceptor` as the module's hook instead of `auth.type`'s. Like
  `ModuleClients.configure` it accepts an external or loopback module only (D-83); its interceptor needs no Spring
  Security. Every hook runs after the interceptors an R-EXT-06 customizer adds.
- **One hook per module.** Two `ModuleClients.authenticate` beans for one module, or one together with an explicit
  `auth.type`, fail startup with TL0036 naming the module. A request that reaches the hook already carrying an
  `Authorization` header (a customizer's) and whose `Authorization` the hook changes fails before it is sent with a
  `TopolithException` carrying TL0036 (R-EXT-11's `exception` is `undeclared`), so two credential sources never pick
  a winner silently (INV-4).
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
  credentials, and parity tests compare those two (D-94). A caller authenticated without a bearer token (a session
  login, anonymous) relays nothing, and a loopback host with no resource-server configuration does not authenticate a
  relayed token, so in loopback and external mode the provider sees whatever its security chain makes of a request
  without credentials; these are documented gaps that loopback shows before extraction, not startup failures (D-95).
  `client-credentials` deliberately relaxes this parity: the provider sees the client's principal in loopback and
  external mode and the caller's in internal mode.
- **Activation.** Only an external or loopback module's client gets a hook; a `none` or internal module has no client
  and no auth interceptor (INV-2). Spring Security stays an optional dependency of `topolith-spring-boot`, used behind
  class checks (INV-7). The relay reads the calling thread's `SecurityContext` at the call and sends a value, so
  nothing assumes the caller and provider share a thread (INV-6); a call made on another thread relays what that
  thread's `SecurityContext` holds, which is also what an internal provider would see there.

Implements INV-2, INV-4, INV-6, INV-7 and D-92 through D-96.

- AC-EXT-53 (M6.4): With `relay`, a loopback and an external call from a thread whose `Authentication` is a
  `JwtAuthenticationToken` send its token as `Authorization: Bearer`, and the provider sees an `Authentication` with
  the caller's name and authorities, as an internal provider does; with no `Authentication` or one without a token the
  call sends no `Authorization` and succeeds.
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
- AC-EXT-55 (M6.4): `ModuleClients.authenticate(<id>, <interceptor>)` installs the interceptor as that module's only
  hook; two hooks for one module, or a hook with an explicit `auth.type`, fail startup with TL0036, and a hook that
  changes an `Authorization` header a customizer set fails the call with a `TopolithException` carrying TL0036
  without sending it.
- AC-EXT-56 (M6.4): An `auth.*` key for an internal or `none` module, an unknown key or value, an
  `auth.registration-id` without `client-credentials` and a `ModuleClients.authenticate` for an internal module fail
  with TL0003; `client-credentials` without the OAuth2 client classes, without a registration id, with an unknown one,
  without a `ClientRegistrationRepository` or `OAuth2AuthorizedClientService` bean, with two of either, or with two
  client-credentials `OAuth2AccessTokenResponseClient` beans fails with TL0037; a refresh changing an `auth.*` key logs TL0010 and marks restart pending.
- AC-EXT-57 (M6.4): An internal call's provider sees the caller's `SecurityContext` unchanged; an internal or `none`
  module gets no auth interceptor, and a host without Spring Security on the classpath starts and its loopback and
  external calls send no `Authorization`.
