# External mode

In `external` mode a host calls a module through an HTTP client generated from the module's contract, and imports
nothing of its implementation.

```mermaid
sequenceDiagram
    participant Caller as Caller (host)
    participant Client as Contract client
    participant Svc as payment-service
    Caller->>Client: paymentApi.charge(request)
    Client->>Svc: POST /payment/charges (connect 2s, read 5s)
    alt success
        Svc-->>Client: 200 JSON
        Client-->>Caller: ChargeResult
    else declared exception
        Svc-->>Client: application/problem+json
        Client-->>Caller: the same exception type
    else transport failure
        Client-->>Caller: ModuleUnavailableException
    end
```

## The contract

By default the `*-api` contract interface carries `@HttpExchange`, and the module's `@RestController` implements it
(it may delegate to a service). External mode registers one HTTP service group, named after the module, for its
provided contracts. A host needs `spring-boot-starter-restclient`; without an HTTP client stack, or with a contract
that has no `@HttpExchange`, startup fails with `TL0026`.

If a contract should not depend on `spring-web`, keep a separate HTTP interface that extends it and map it with
`topolith.modules.<id>.remote.http-interface.<contract FQN>`. The keys are case-sensitive class names, so the
property source must preserve case (environment variables do not).

## Choosing the endpoint

Set exactly one of `remote.url` (a fixed endpoint) or `remote.service-id` (discovery), never both:

<!-- snippet: samples/hosts/hybrid/src/main/resources/application.yml#hybrid-modes -->
```yaml
topolith:
  modules:
    user:
      mode: internal
      expose:
        enabled: true
    payment:
      mode: external
      remote:
        # payment-service's port (8081); HybridPaymentTest overrides it with the container's mapped port
        url: http://localhost:8081
        connect-timeout: 2s
        read-timeout: 5s
```

The `connect-timeout` and `read-timeout` values shown are the defaults.

- `remote.url` is combined with the provider's fixed `/<id>` prefix and becomes the group's base URL.
- `remote.service-id` resolves `lb://<service-id>/<id>` through Spring Cloud LoadBalancer and any `DiscoveryClient`.
  It needs `spring-cloud-starter-loadbalancer` (`TL0009` otherwise).

Neither or both, or a value that contradicts an explicit Boot `spring.http.serviceclient.<id>` property, fails with
`TL0003`.

## Timeouts

Every group has timeouts, with defaults of 2 seconds to connect and 5 seconds to read. Override them with
`remote.connect-timeout` and `remote.read-timeout`. Boot's `spring.http.serviceclient.<id>.*` and
`spring.http.clients.*` timeouts also replace the defaults, but setting both a Topolith and a Boot timeout for the same
group to different values fails with `TL0003`. See the [configuration reference](guide/configuration.md).

## Errors

- **Declared exceptions** (unchecked, in the contract's API packages) cross HTTP as `application/problem+json` and are
  rebuilt as the same type on the caller's side. The message is carried; the caller's HTTP exception travels as a
  suppressed exception.
- **Transport failures** (connection refused, timeouts, 502, 503 or 504 without a Topolith problem, no instances from
  the load balancer, an open circuit) raise `ModuleUnavailableException`. Its message names only the module and the
  kind of failure, never a URL or downstream body. After a read timeout the call may have run.
- **Anything else** raises an unchecked exception whose type is unspecified. An undeclared exception's message is not
  sent by Topolith.

A contract method that declares a checked exception fails startup with `TL0027`.

## Exposing a module

A module served by a `*-service`, or by a host that others call, exposes its routes under `/<id>` when
`topolith.modules.<id>.expose.enabled` is `true`. It defaults to `false` everywhere, so a monolith serves nothing by
default. Only an `internal` module in a servlet application can be exposed (`TL0003`, `TL0028`). For security, Topolith
registers no filter chain; it provides the `ExposedEndpoints` and `ExposedRequestMatcher` beans for your own
`SecurityFilterChain`.

## Version readiness

At readiness the host compares the major `api-version` of its `*-api` JAR with the remote provider's, read from
`/<id>/.topolith/api-version`. A mismatch logs `TL0023`; an unreachable or unreadable endpoint logs `TL0015`. Neither
fails startup.

## Refreshing an endpoint

With `topolith-spring-cloud`, changing a URL, a service id (to another service id) or a timeout in the environment
rebuilds that client in place; calls in flight finish on the old client. Invalid settings keep the old client and log
`TL0011`. Switching between URL and service id needs a restart (`TL0010`, reported as `restartPending`).

## Customizing the client

`ModuleClients.configure("payment", customizer)` adjusts a module's client. A customizer cannot replace the base URL,
request factory, interceptors or message converters.
