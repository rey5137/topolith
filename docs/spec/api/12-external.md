# 12 — External contracts and clients

This file specifies external HTTP clients, discovery, errors, exposure and endpoint refresh.
It also specifies the internal transaction boundary required by the 0.1 plan.

## Rules

### R-EXT-01 — Contract and HTTP service group

By default the `*-api` contract uses `@HttpExchange`, a service implements it, and a thin `@RestController`
exposes it. External mode registers one HTTP service group per module for its provided contracts. A separate HTTP
interface remains supported. Implements INV-5, INV-8 and D-3.

- AC-EXT-01 (M2.1): An external contract call uses the HTTP service group named for its module.
- AC-EXT-02 (M2.4): An annotated contract implemented by a service is exposed through a thin controller.
- AC-EXT-03 (M2.5): A separate HTTP interface can serve a contract without `spring-web` in the contract API.

### R-EXT-02 — Endpoint and discovery

Exactly one of `remote.url` and `remote.service-id` is required for an external module; otherwise fail with TL0003.
Map `remote.url` to `spring.http.serviceclient.<id>.base-url`. Service ids use `lb://` and Spring Cloud LoadBalancer
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
- AC-EXT-05 (M2.2): A service id resolves through LoadBalancer and a configured `DiscoveryClient`.
- AC-EXT-06 (M2.2): Service-id configuration without the required Cloud integration fails with TL0009 and a
  starter hint.

### R-EXT-03 — Timeouts

Every group has connect and read timeouts, defaulting to two and five seconds respectively; each group can override
both. Set `topolith.modules.<id>.remote.connect-timeout` and `remote.read-timeout`, or the corresponding Boot
group properties; Boot-only values, including the global `spring.http.clients.*` timeouts, replace the defaults. Implements INV-4, P-2 and D-40.

- AC-EXT-07 (M2.1): An unconfigured client uses a two-second connect timeout and a five-second read timeout.
- AC-EXT-08 (M2.1): Group timeout overrides apply to that group's client.

### R-EXT-04 — Errors

API-declared exceptions cross HTTP as ProblemDetail and are reconstructed as the same exception type; transport
failures raise `ModuleUnavailableException`. Implements INV-6 and D-3.

- AC-EXT-09 (M2.3): A declared API exception returns as ProblemDetail and reconstructs the same type at the caller.
- AC-EXT-10 (M2.3): A transport failure raises `ModuleUnavailableException` at the caller.

### R-EXT-05 — Exposure

Each module's exposed endpoints use the fixed `/<id>` prefix. `expose.enabled` controls exposure, which is off in a
monolith; exposed endpoints have a `SecurityFilterChain` hook. One application has one discovery registration.
Implements INV-8 and P-2.

- AC-EXT-11 (M2.4): An exposed module's endpoint uses its fixed id prefix in each topology.
- AC-EXT-12 (M2.4): A monolith defaults exposure off, and disabling exposure removes that module's routes.
- AC-EXT-13 (M2.4): A host can supply security through the exposed-endpoint `SecurityFilterChain` hook.
- AC-EXT-14 (M2.4): An application exposing multiple modules registers once with discovery.

### R-EXT-06 — Client customization

The host can configure a module client with `ModuleClients.restClientBuilder(<id>)`. Implements P-2.

- AC-EXT-15 (M2.5): Custom client configuration applies to the named module's group.

### R-EXT-07 — Internal transaction boundary

An internal contract call suspends the caller's transaction with `NOT_SUPPORTED`; the callee's own transaction
annotation decides whether it starts one. An active caller transaction emits a warning metric. Implements INV-6, D-2.

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
