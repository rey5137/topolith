# Configuration Reference

Topolith resolves module modes once at context refresh from `topolith.*` properties. Any unsatisfiable
or ambiguous topology fails startup with a `TLnnnn` diagnostic code and an actionable fix message.

## Modes and Status Vocabulary

These terms are used in descriptors, configuration, diagnostics and the actuator endpoint:

| Term | Meaning |
|---|---|
| Internal | Mode that runs a module's implementation in the host. |
| External | Mode that calls a module through a remote client. |
| None | Mode that does not activate a module or its client. |
| Restart pending | Configuration requests wiring or a mode different from the running topology. |

## Configuration Properties

| Property | Type | Default | Refresh | Invalid |
|---|---|---|---|---|
| `topolith.modules.<id>.mode` | `Mode` | required | Restart (`TL0010`) | `TL0001`, `TL0002`, `TL0004`, `TL0016` |
| `topolith.standalone` | `String` | — | Restart (`TL0010`) | `TL0025` |
| `topolith.modules.<id>.remote.url` | `URI` | — | Refresh (`TL0010`, `TL0011`) | `TL0003` |
| `topolith.modules.<id>.remote.service-id` | `String` | — | Refresh (`TL0010`, `TL0011`) | `TL0003`, `TL0009` |
| `topolith.modules.<id>.remote.connect-timeout` | `Duration` | `2s` | Refresh (`TL0011`) | `TL0003` |
| `topolith.modules.<id>.remote.read-timeout` | `Duration` | `5s` | Refresh (`TL0011`) | `TL0003` |
| `topolith.modules.<id>.remote.http-interface.<contract FQN>` | `String` | — | Restart | `TL0003`, `TL0026` |
| `topolith.modules.<id>.expose.enabled` | `boolean` | `false` | Restart | `TL0003`, `TL0028` |

### Property Details

- **`topolith.modules.<id>.mode`:** Required explicit mode (`internal`, `external` or `none`,
  trimmed and case-insensitive) for each known module (a module with a module or API descriptor on the classpath).
  Default: required — absent or invalid values fail startup with `TL0001`, except that the module named by
  `topolith.standalone` defaults to `internal`. Setting `internal` without the module implementation descriptor JAR
  fails with `TL0002`; setting `external` or `none` for an unknown module id fails with `TL0016`; an `internal` module
  whose required dependency is `none` or unknown fails with `TL0004`. An `external` module requires
  `spring-boot-starter-restclient` on the host classpath (`TL0026`). On an `EnvironmentChangeEvent` (with
  `topolith-spring-cloud`), a mode change logs warning `TL0010` naming the running and requested modes and marks
  `restartPending` (`mode`) on the actuator endpoint without altering the running topology.
- **`topolith.standalone`:** Names the single module id that a standalone `*-service`
  defaults to `internal`. Exempts that module from host scan overlap (`TL0018`) and default-candidate infrastructure
  bean checks (`TL0019`). Fails with `TL0025` if any other module with a module descriptor is `internal`. Refreshing
  `topolith.standalone` re-evaluates requested modes, logs `TL0010` on any difference and marks `restartPending`.
- **`topolith.modules.<id>.remote.url` and `topolith.modules.<id>.remote.service-id`:** Every
  `external` module must set exactly one of `remote.url` (mapped as `<url>/<id>` to
  `spring.http.serviceclient.<id>.base-url`) or `remote.service-id` (mapped as `lb://<service-id>/<id>` for Spring
  Cloud LoadBalancer and a `DiscoveryClient`); setting neither or both, an unparsable URL, or a conflicting explicit
  Boot `base-url` fails with `TL0003`. An external module requires `spring-boot-starter-restclient` (`TL0026`), and
  using `remote.service-id` requires `spring-cloud-starter-loadbalancer` (`TL0009`). With `topolith-spring-cloud`,
  refreshing `remote.url` to a new URL or `remote.service-id` to a new service id rebuilds the HTTP client in place
  (logging `TL0011` and keeping the running client if invalid); switching between `remote.url` and `remote.service-id`
  logs `TL0010` and marks `restartPending` (`wiring`).
- **`topolith.modules.<id>.remote.connect-timeout` and `topolith.modules.<id>.remote.read-timeout`:** Per-group connect and read timeouts, defaulting to `2s` and `5s` unless overridden by Boot's
  `spring.http.serviceclient.<id>.*` or `spring.http.clients.*` timeouts. Invalid `Duration` values or conflicting
  explicit Topolith and Boot group timeouts fail startup with `TL0003`. Refreshing timeouts with
  `topolith-spring-cloud` rebuilds the client in place (or logs `TL0011` on invalid values).
- **`topolith.modules.<id>.remote.http-interface.<contract FQN>`:** Maps a provided contract class name
  to a loadable sub-interface carrying `@HttpExchange` when the contract API itself avoids `spring-web`. Requires a
  case-preserving property source (environment variables are unsupported). Fails with `TL0003` when the module is
  unknown or not `external`, the key is malformed, or the contract is not in `provides`; fails with `TL0026` when the
  mapped interface cannot be loaded, does not extend the contract, or has no `@HttpExchange`.
- **`topolith.modules.<id>.expose.enabled`:** Controls exposure of an `internal` module's web routes under
  `/<id>` and its `/<id>/.topolith/api-version` endpoint. Defaults to `false` in every topology so a monolith exposes no
  module routes unless opted in. Fails with `TL0003` if non-boolean, set on a non-internal module, or set under
  `expose.*` for an unknown module id; fails with `TL0028` when enabled in a reactive WebFlux application.

## Compile-Time Annotation Processor Option

| Option | Type | Default | Description & Diagnostic |
|---|---|---|---|
| `-Atopolith.apiVersion` | `String` | required | Sets `api-version` in descriptors (`TL0013` if blank). |

## API Version Readiness and Actuator Status

- **API version readiness:** At application readiness, Topolith compares each host `<id>-api` descriptor's
  major `api-version` against the internal module descriptor's major version, or for an `external` module against
  `GET /<id>/.topolith/api-version` (using the group's mandatory timeouts). A major version mismatch logs
  warning `TL0023` once; an unreachable or non-2xx remote version endpoint (or unreadable body) logs warning `TL0015`.
  Neither fails startup nor marks an otherwise-ready host unready.
- **Actuator endpoint (`topolith`):** Read-only `@Endpoint(id = "topolith")` (exposed through Spring Boot
  Actuator's `management.endpoints.web.exposure.include`). Reports top-level `restartPending` (`boolean`) and a
  `modules` map keyed by known module id containing `mode`, plus optional `requestedMode`, `restartPending` (`["mode"]`,
  `["wiring"]`), and `refresh` (`status: "failed"`, `code: "TL0011"`, `message`, `at`).
- **Refresh dependency warning:** If Spring Cloud context classes are present at startup without
  `topolith-spring-cloud`, Topolith logs warning `TL0014`.

## Examples

### Monolith Host (`internal` modules)

<!-- snippet: samples/hosts/monolith/src/main/resources/application.yml#monolith-modes -->
```yaml
topolith:
  modules:
    user:
      mode: internal
      expose:
        enabled: true
    payment:
      mode: internal
```

### Hybrid Host (`internal` and `external` modules)

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

### Standalone Service (`topolith.standalone`)

<!-- snippet: samples/payment/payment-service/src/main/resources/config/payment-service/application.yml#payment-service-modes -->
```yaml
topolith:
  standalone: payment
  modules:
    payment:
      expose:
        enabled: true
```
