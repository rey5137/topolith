# RFC 0003 — Gateway audiences

- **Status:** draft
- **Affects:** Q-34 (`docs/spec/reference/92-decisions-questions.md`); D-27, D-44, D-46, D-127, D-129, D-136;
  R-DESC-*, R-EXT-04, R-EXT-05, R-EXT-12; TL0028, TL0048; INV-1, INV-2, INV-3, INV-4, INV-9 (applied, not changed)
- **Decisions:** proposed G-1 to G-9 below, numbered D-138 onward on acceptance
- **Discussion:** none (owner decision)

## Summary

A module today publishes one HTTP surface: its contract routes under `/<id>`, which only Topolith's own clients call
(R-EXT-05). This RFC adds a second, gateway-facing surface: **audiences**. An audience (`mobile`, `partner`, `web`)
is a named set of a module's handler methods meant for one kind of client, declared down to the method, so one
controller can serve both contract and audience routes. A host maps an audience's routes only when it lists the
audience, and a host that is the **gateway** for an audience routes each client request to the module that serves it:
in-process when the module is internal there, over HTTP when it is external. One host can be the gateway for several
audiences. Requests and responses pass through a filter chain of Spring's `HandlerFilterFunction`s, with Spring Cloud
Gateway Server MVC's filter library and shortcut names, so a filter can read, reject or rewrite the request (method,
path, query, headers, body) and the response. Moving a module between hosts changes the route's target, never its
filters or its client-facing path.

## Motivation

The modular monolith is usually reached by more than one client: a mobile app, a web front end, partner
integrations. Each wants its own paths, auth, envelope and error shape, and today an adopter has three bad options:

1. **Expose the contract routes** (`expose.enabled=true`). These carry `Topolith-*` headers, Topolith problems and
   contract-shaped payloads (R-EXT-04, R-EXT-12). They are a module-to-module wire, not a client API (D-127).
2. **Write plain controllers and route to them by hand.** It works in one topology. Once a module moves to its own
   service, every gateway route that pointed at the monolith must be rewritten, and nothing checks that the routes,
   paths and filters still agree with the topology (INV-3, INV-4). D-44 also unmaps every handler of an unexposed
   module, so such a controller is not even reachable in the monolith.
3. **Put a separate gateway in front and hand-maintain its route table.** Same drift, plus a second deployment even
   in the monolith.

The concrete case behind Q-34: one module serves both a mobile app and partner callers. The mobile routes need the
user's token passed on, a `X-Client-Version` header checked and rejected below a minimum, a legacy path rewritten,
and a response envelope; the partner routes need an API key and a different error shape. The same module runs
in-process in the monolith and as its own service in production, and the monolith hosts both gateways. The gateway
behaviour must not change between the two placements.

The owner also asked that a gateway can **intercept and modify** a request, not only validate it, which Q-34's
validate-only `AudienceGuard` sketch does not allow.

## Design

### 1. Declaring an audience (G-1)

`@Audience` is a new annotation in `io.github.rey5137.topolith.contract` (so INV-1 holds), with RUNTIME retention,
allowed on a handler method, a controller class, or a controller package's `package-info.java`:

```java
@RestController
@RequestMapping("/orders")
class OrderController implements OrderLookup {          // OrderLookup is a contract the module provides

    @Override
    public OrderView find(String id) { ... }             // contract route: /order/...

    @Audience({"mobile", "web"})
    @GetMapping("/{id}/summary")
    OrderSummary summary(@PathVariable String id) { ... } // audience routes: /mobile/order/orders/{id}/summary, /web/...
}
```

- **Precedence:** method over class over package. The nearest declaration replaces the others, never merges.
  `@Audience({})` on a method opts it out of its class's or package's audiences.
- An audience name is a lowercase run of letters, digits and `-` (as a module id).
- **One method, several audiences.** `@Audience({"mobile", "web"})` maps the same handler method once per listed
  audience the host serves, each under its own prefix, and each gateway route gets its own audience's filters. The
  handler reads which audience it was called through from the request attribute `Audience.REQUEST_ATTRIBUTE` (the
  audience name), for example to shape a response per client. Advice is scoped by type (below), so the same method
  cannot have a different `@ExceptionHandler` per audience; a per-audience error shape on a shared method is a
  response-side gateway filter, or the handler branching on the attribute.
- **Mixing is allowed** in one controller. A handler method that implements a contract method and has an audience,
  declared or inherited from its class or package, fails startup (new TL code): its route would count toward D-126
  coverage while carrying none of the contract wire's behaviour. A class or package whose `@Audience` would reach a
  contract method must opt that method out with `@Audience({})` or move the annotation to the methods.
- `@Audience` on a method that is not a handler, on a class the module does not own (D-129), or on a package with no
  handler fails startup (new TL code).
- **Classification.** One registry classifies every `HandlerMethod` as a contract route, an audience route (with its
  audience set), a plain module route, or a host route. Everything Topolith decides "per handler's module" today
  consults it per method, not per bean type: R-EXT-12's tenant and context handling, R-EXT-04's problem translation
  (`ModuleExceptionResolver`), D-117's `Topolith-Module` header, R-EXT-11's observation, route marking and D-126/D-129
  coverage. An audience route gets none of the contract-wire behaviour. R-EXT-04's provider side is amended from "a
  module route" to "a module route that is not an audience route". R-EXT-07's boundary covers contract-method calls
  only, so audience methods are untouched by it.
- **What Topolith cannot separate in a mixed class.** Spring scopes `@ControllerAdvice` (`assignableTypes`,
  `basePackages`, `annotations`) and in-class `@ExceptionHandler`, `@InitBinder` and `@CrossOrigin` by type, so they
  apply to both kinds of method. An audience error shape written as in-class `@ExceptionHandler` therefore also
  answers the contract wire's undeclared exceptions. Topolith warns at startup (new TL code, `warning`) when a class
  with both kinds of method declares `@ExceptionHandler`; the fix is advice scoped to audience-only classes, or
  splitting the class. Adopters who want no such coupling keep audience routes in their own controllers.

### 2. Serving an audience in a host (G-2)

```yaml
topolith:
  audiences:
    mobile:
      path: /mobile          # required; the audience's source prefix in every host
    partner:
      path: /partner
```

- A host maps an audience route only when the module is internal (or loopback) **and** the host lists the
  audience, whether or not the module is exposed. Its source path is `<audience path>/<module id><handler path>`,
  for example `/mobile/order/orders/{id}/summary`. A route with two listed audiences is mapped once per audience.
- **The module id segment is forced** in the source path for 0.2. It keeps the gateway's route table to one route
  per (audience, module) and makes two modules' audience routes unable to clash. It can be relaxed later without a
  break: the key `topolith.audiences.<a>.modules.<id>.path` is reserved now and fails startup if set (new TL code),
  and every uniqueness check is written over resolved source prefixes, not over `<audience path>/<id>`. Relaxing
  it later means letting that key take a value and recording it in the descriptor (§6), an additive change. The
  client-facing path is already free (§3).
- **Mapping mechanism.** D-44's post-processor, which today unregisters every handler of an unexposed module, is
  amended: for each audience method of an internal or loopback module it unregisters the host-detected mapping and
  registers one mapping per listed audience with `info.mutate().paths(<audience path>/<id> + original path)`, so the
  host's custom conditions, CORS settings and API version strategy are kept. A clash with another mapping fails with
  TL0048 (D-136). Contract methods of the same class keep today's behaviour: under `/<id>` when exposed or loopback,
  unmapped otherwise. No `WebMvcRegistrations` bean or `getMappingForMethod` override is used, since Boot reads
  `WebMvcRegistrations` only when unique and a host's own would silently switch Topolith's off (INV-4). D-44 and
  R-EXT-05 are amended to say so.
- A module that is `none` or `external` maps no audience route (INV-2). An audience the host lists that no module
  of the host's topology declares (by descriptor, §6), two audiences with the same or nested paths, or an audience
  path that overlaps a module's `/<id>` contract prefix fail startup (new TL codes).
- Audience routes are plain Spring MVC routes: Spring's own `@ControllerAdvice`, Spring MVC's server observation and
  the host's security chain apply. A public `AudienceRequestMatcher` bean per listed audience, like
  `ExposedRequestMatcher` (D-46), lets the host's `SecurityFilterChain` protect them.
- `topolith.audiences.*` is part of the topology: fixed at refresh, and at build time for AOT (INV-9). A
  package-level `@Audience` gets an AOT reflection hint for its `package-info` class.

### 3. The gateway role (G-3)

A host is the gateway for an audience when it says so. One host may be the gateway for several audiences:

```yaml
topolith:
  audiences:
    mobile:
      path: /mobile
      gateway:
        enabled: true
        path: /api/mobile              # client-facing prefix; defaults to `path`
        modules:
          order:
            path: /api/mobile/orders   # client-facing prefix for this module; defaults to <gateway path>/<id>
    partner:
      path: /partner
      gateway:
        enabled: true
        path: /api/partner
```

For each module of the gateway host's topology whose descriptor declares the audience (§6), Topolith registers one
gateway route, `<module's client prefix>/**`, as a `RouterFunction` whose target strips the client prefix and adds the
module's resolved source prefix:

| Module mode in the gateway host | Target |
|---|---|
| `internal` | in-process, to `<audience path>/<id>/…` (§5) |
| `loopback` | HTTP to this host's own `<audience path>/<id>/…`, through R-EXT-10's latency and faults |
| `external` | HTTP to the module's endpoint (R-EXT-02: fixed URL, or service id through Spring Cloud LoadBalancer), with the module's R-EXT-03 timeouts |
| `none` | no route; the startup log and the actuator list the module as not served |

- **Topolith builds the targets** (`HandlerFunction`s); SCG supplies the filters (§4). SCG's own `http()` handler
  cannot apply a module's R-EXT-03 timeouts per route or R-EXT-10's faults, and its `forward()` loses request
  rewrites (§5), so neither is used as a target.
- **Several gateways in one host.** Each audience's routes, client prefixes, filters (rate limiters included) and
  security chain (one `SecurityFilterChain` per `AudienceRequestMatcher`) are per route and fully separate. These are
  process-wide and shared by every audience the host gateways: one server port, `server.forward-headers-strategy`,
  SCG's `spring.cloud.gateway.server.webmvc.*` settings (trusted proxies, `X-Forwarded-*` handling), and a rate
  limiter's backing store. An audience on its own port is out of scope for 0.2; it needs its own host.
- Client prefixes of all gateway routes in a host must not overlap each other, an audience source path or a `/<id>`
  contract prefix (new TL code). `gateway.modules.<id>.*` for a module that is `none`, unknown, or doesn't declare
  the audience fails startup (INV-4).
- The gateway host states every module's mode (INV-3), so a "per-host module subset" (Q-34) is just its topology.
- The gateway needs `spring-cloud-starter-gateway-server-webmvc` and the new optional module
  `topolith-spring-cloud-gateway` (INV-7). `gateway.enabled=true` without them fails startup naming the dependency
  (new TL code). A reactive host cannot be a Topolith gateway (TL0028 extended), but may consume the route table (§6).
- The HTTP target uses the module's endpoint and timeouts but not its contract client: no retry (R-EXT-14) or breaker
  (R-EXT-15) unless the audience's filters add SCG's `Retry` or `CircuitBreaker`.

### 4. Filters (G-4, the owner's request)

A filter is a Spring Framework `HandlerFilterFunction<ServerResponse, ServerResponse>`: `filter(request, next)`
returns the response, so it can reject (return without calling `next`), rewrite the request
(`ServerRequest.from(request)…build()`) and change the response. Filters are listed per audience, and optionally per
(audience, module), in SCG Server MVC's shortcut syntax, which resolves SCG's built-in filters and the adopter's:

```yaml
topolith:
  audiences:
    mobile:
      gateway:
        filters:                          # every mobile route, in this order
          - RequireClientVersion=2.4      # an adopter's filter, below
          - AddRequestHeader=X-Audience, mobile
        modules:
          order:
            filters:                      # run after the audience's
              - RewritePath=/v1/(?<rest>.*), /${rest}
```

An adopter's filter is a standard SCG Server MVC custom filter, registered as a `SimpleFilterSupplier` `@Bean`, with
no Topolith type:

```java
public final class ClientVersionFilters {
    public static HandlerFilterFunction<ServerResponse, ServerResponse> requireClientVersion(String minimum) {
        return (request, next) -> {
            var version = request.headers().firstHeader("X-Client-Version");
            if (version == null || Versions.lessThan(version, minimum)) {
                return ServerResponse.status(426).build();                       // reject
            }
            var rewritten = ServerRequest.from(request)                           // modify
                    .header("X-Client-Major", Versions.major(version))
                    .build();
            var response = next.handle(rewritten);
            response.headers().add("X-Min-Version", minimum);                     // decorate, body kept
            return response;
        };
    }
}

@Bean
SimpleFilterSupplier clientVersionFilters() {
    return new SimpleFilterSupplier(ClientVersionFilters.class);
}
```

Why this shape rather than a Topolith `AudienceFilter` SPI:

- **P-5.** The filter type is Spring Framework's own and already has the requested shape; SCG adds ~40 built-in
  filters (`RewritePath`, header and path filters, `ModifyRequestBody`, `ModifyResponseBody`, `RequestRateLimiter`,
  `Retry`, `CircuitBreaker`, `TokenRelay`) and the shortcut-name resolution. A Topolith SPI would duplicate all of it.
- **Adopters already know it**, and a filter written for a hand-built SCG Server MVC gateway works unchanged.
- **INV-1 holds** without an extra rule: filters are Spring and SCG types, never Topolith types.
- **Order is explicit.** Filters run in list order, audience list then module list, so there is no `@Order` tie for
  INV-4 to police. A filter name that cannot be resolved fails startup (new TL code), never skipped.

Topolith adds only what SCG cannot know: the route table derived from the topology, the in-process or HTTP target per
mode, and the checks in §2, §3 and §5. Whether SCG's shortcut resolution can be called from a library without its
internal classes is the spike's first question; if not, Topolith resolves names against `FilterSupplier` beans itself,
using SCG's public `FilterSupplier` contract.

### 5. Parity across placements (G-5)

INV-6 is about contract calls, but the same promise matters here: an audience route must behave the same whether
the gateway reaches it in-process or over HTTP.

- **Filters see HTTP only.** A filter receives `ServerRequest`/`ServerResponse` in every placement, never a handler,
  a bean or a Java result.
- **The in-process target honours every rewrite.** SCG's `forward()` dispatches the original servlet request, so a
  rebuilt `ServerRequest`'s method, URI and headers would be lost in-process and kept over HTTP. Topolith's internal
  target therefore dispatches a servlet request wrapper built from the final `ServerRequest` (method, URI, query,
  headers, body) and buffers the response for the response-side filters. Every internal route uses it; no route
  switches target by which filters it has, and no filter may
  silently do nothing in one placement (for example `ModifyResponseBody` without a proxied response stream). A
  built-in filter that cannot be made to work in-process is rejected at startup on internal targets (new TL code).
- **Body limit.** A body a filter buffers, request or response side and chunked included, is limited by the
  audience's `gateway.max-body-size` (default 256 KiB, as Spring WebFlux's codecs): over it answers 413 (request) or
  502 (response) before the filter sees it. Routes whose filters buffer nothing keep streaming.
- **Security and observation parity.** Spring Security's default dispatcher types (`REQUEST`, `ASYNC`, `ERROR`) skip
  an in-process dispatch, while the HTTP placement runs the target host's chain. The internal target runs the host's
  `SecurityFilterChain` for the audience's `AudienceRequestMatcher` on its dispatch, and records Spring MVC's server
  observation the same way, so both placements see one security decision and one server observation per hop. ACs test
  both.
- **Loopback proves it.** A loopback module goes over real HTTP to its own host (§3), so a test that runs the gateway
  routes with the module `internal` and again `loopback` catches a filter that works only one way.
  `topolith-spring-test` gets a helper that runs one test class in both placements, as M5 did for contract calls.
  Topolith's own ACs run each built-in filter family in both.

### 6. Descriptor and what the gateway publishes (G-6)

- **Descriptor.** A gateway host cannot read an external module's classes, so the processor records each module's
  audience names in its module descriptor as an optional `audiences` list (D-27: unknown fields ignored by older
  readers, the list absent when there are none). Paths stay out of the descriptor while the source segment is forced
  (§2). A host's own internal modules are checked against their classes at startup; a descriptor that disagrees with
  the classes fails startup (new TL code), as for `provides`.
- **Actuator.** The `topolith` endpoint gains an `audiences` section: per audience, its source path and, in a gateway
  host, per module the client prefix, resolved source prefix, target (in-process, loopback, URL or service id) and
  resolved filter list. A reactive SCG gateway or a non-Spring gateway can read it to build its own table, and must
  use the resolved source prefix rather than assume `<audience path>/<id>`. Topolith generates no other gateway's
  configuration in 0.2.

### 7. Source-side behaviour (G-7)

Q-34 proposed an `AudienceAdvice` type for the envelope and error shape applied in every host serving the audience.
This RFC drops it: an audience's error shape is the module's own `@RestControllerAdvice`, which runs wherever the
module does (see §1 for mixed classes). A contract call from an audience handler can throw
`ModuleUnavailableException`; with nothing handling it the client gets Spring's default 500. Topolith logs a startup
warning (new TL code, `warning`) naming the audience and handler when Spring's `ExceptionHandlerMethodResolver` finds
no `@ExceptionHandler` for it on the controller or on any `ControllerAdviceBean` that applies to the controller. It
is a warning, not a failure, because a host with its own `HandlerExceptionResolver` may handle it in ways the check
cannot see.

### 8. Gateway bypass and trust (G-8)

- **Direct access in the gateway host.** In the monolith, `<audience path>/<id>/…` is reachable directly, skipping
  the gateway's filters. A host that is the gateway for an audience answers 404 to a direct request (dispatcher type
  `REQUEST`) to that audience's source paths; only its own in-process target reaches them. A host that serves an
  audience without being its gateway accepts direct requests, since the gateway reaches it over HTTP.
- **Direct access to proxied hosts.** Those hosts' audience routes are reachable without the gateway too. The
  recommended setup, shown in the samples, is **token pass-through**: the gateway keeps the client's `Authorization`
  header, and each host validates the token as an OAuth2 resource server on its `AudienceRequestMatcher`. SCG's
  `TokenRelay` is different: it sends the gateway's own OAuth2-client token for a logged-in session, for gateways that
  hold the login (the web audience). Either way the gateway's checks are a convenience, never the only gate. Mutual
  TLS or a network policy are the adopter's choice; Topolith ships no gateway-signed header, which would be a custom
  protocol (INV-8) and forgeable without key management.

### 9. Scope (G-9)

In 0.2: §1 to §8 on servlet hosts. Out: a source path override (reserved, §2), a gateway audience on its own port,
OpenAPI groups per audience (a springdoc `GroupedOpenApi` per audience is a small follow-up), WebSocket and SSE
through the gateway, reactive gateways hosting modules, generated configuration for non-Spring gateways.

### New ids (numbered at acceptance)

- **Rules:** R-AUD-01 audience declaration and classification, R-AUD-02 audience routes in a host, R-AUD-03 gateway
  routes and targets, R-AUD-04 filters, R-AUD-05 placement parity, R-AUD-06 bypass, in a new child file
  `api/14-audiences.md`; amendments to R-DESC (the `audiences` field), R-EXT-04 and R-EXT-05, D-44.
- **TL codes** (TL0049 onward): audience on a contract method; misplaced `@Audience`; `@ExceptionHandler` in a mixed
  class (warning); unknown or undeclared audience; audience or gateway path clash; reserved source-path key set;
  stray gateway module config; gateway enabled without SCG Server MVC; unresolved filter name; filter unsupported on
  an internal target; descriptor audiences disagree with classes; `ModuleUnavailableException` unhandled on an
  audience route (warning). TL0028 and TL0048 widen.
- **ACs:** each rule's ACs include an INV-2 test (a `none` module leaves no audience route and no gateway route), a
  placement-parity test (internal and loopback) per built-in filter family, a security and observation parity test,
  a two-gateways-in-one-host test, and a mixed-controller test (contract method keeps the Topolith wire, audience
  method gets none of it).

## Compatibility

Additive. `@Audience`, the `topolith.audiences.*` keys and the descriptor's `audiences` field are new and `@Incubating`
in 0.2. A host that lists no audience behaves exactly as in 0.1, including D-44's unmapping. The descriptor change is
an optional field that 0.1 readers ignore (D-27); a 0.1-built module has no audiences, so a 0.2 gateway routes none of
its routes. Relaxing the forced source segment later is additive (§2).

## Alternatives

- **A Topolith `AudienceFilter` SPI** (`filter(exchange, chain)`). One shape on every gateway, reactive included, and
  no SCG dependency. Lost: Spring Framework's `HandlerFilterFunction` already has the shape, and the SPI would
  duplicate SCG's built-in filters, so adopters would write every filter twice.
- **SCG's own `forward()` and `http()` as targets.** Less code. Lost: forward drops request rewrites, and `http()`
  can't apply per-module timeouts or loopback faults.
- **Forbid mixing contract and audience methods in one controller** (the earlier draft). Simpler classification, but
  forces a split controller for every mixed surface; the per-method registry is needed anyway for multi-audience
  classes, and the remaining leak (type-scoped advice) is warned about.
- **Q-34's `AudienceGuard` + `AudienceAdvice`.** Lost: it cannot rewrite a request, which the owner requires, and it
  duplicates `@ControllerAdvice`.
- **A plain servlet `Filter` per audience in each host.** No route awareness, no proxy, request mutation needs
  wrappers, and it sees in-process state the HTTP path never has, which breaks placement parity.
- **Source paths without the module id**, with the gateway's table built from each handler's path. It needs every
  path in the descriptor and makes two modules' routes clash; the gateway's per-module client prefix gives the same
  client paths. Kept relaxable (§2).
- **Generating `spring.cloud.gateway.server.webmvc.routes` properties** instead of `RouterFunction` beans. The target
  depends on the topology and loopback's real port, which properties can't express cleanly.
- **Do nothing.** Adopters keep option 2 or 3 from Motivation, and every topology change silently breaks a gateway.

## Unresolved questions

Owner decisions: method-level `@Audience` with mixing, and several audiences per method (G-1); forced source segment
for now, relaxable later (G-2); several gateways per host (G-3); SCG Server MVC's filters (G-4); the in-process target
is a servlet-request wrapper built from the final `ServerRequest` with a buffered response (G-5); a gateway host
answers 404 to direct requests on its own audiences' source paths (G-8); `ModuleUnavailableException` unhandled on an
audience route is a startup warning (G-7); `gateway.max-body-size` defaults to 256 KiB, buffered bodies only (G-5).

Left to a spike before the plan, not to the owner: one module, two audiences in one host, internal and loopback, with
`RewritePath`, `AddRequestHeader`, `ModifyRequestBody`, `ModifyResponseBody` and pass-through auth. It confirms that the
wrapper target honours every rewrite and security dispatch (if it cannot, the fallback is loopback HTTP for every
internal target, which returns to the owner), and whether SCG's shortcut resolution is usable from a library (§4).
