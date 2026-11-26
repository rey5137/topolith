# 21 — Test support

This file specifies 0.1 test helpers for selecting module modes, wiring external dependencies and passing internal
calls by value. It also specifies the shared tests for the reference topologies and the mode matrix.

## Rules

### R-TEST-01 — Mode configuration

`topolith-spring-test` supplies per-mode test configuration for the supported 0.1 modes. Implements INV-3, P-3, D-18 and D-62.

- AC-TEST-01 (M3.4): A test can select `internal`, `external` or `none` for a module through the helper. The
  annotation overrides application config only: when inlined test properties (`@SpringBootTest(properties)`,
  `@TestPropertySource(properties)`) or dynamic properties set the same `mode`, `remote.url` or `remote.service-id` to a
  different value, context load fails with `IllegalStateException` naming the module, the key and both values; a
  same-valued property passes. The check covers `@TestPropertySource` files (also with a placeholder location or a
  custom `factory`) and `DynamicPropertyRegistrar` beans, and annotations on a superclass or enclosing class. An
  `external`
  selection (annotation or builder) sets `remote.service-id` to the empty string, so a host that configures a service id
  starts and the mock URL wins. Selecting `internal` or `none` leaves a host service id untouched. No selection clears
  a host's explicit `spring.http.serviceclient.<id>.base-url` (AC-TEST-18).
  The builder's `applyTo` throws `IllegalStateException` naming a module that was selected without a mode. The context
  customizer factory and the test execution listener are package-private and load through `spring.factories`.
- AC-TEST-02 (M3.4): Helper-selected `none` leaves no implementation contributions in the test context, also when the
  host configures a service id.
- AC-TEST-16 (M5.3): A test can select `loopback` through the annotation and the builder's `loopback()`; the selection
  sets `remote.url` and `remote.service-id` to the empty string, so a host that configures either starts in loopback,
  and R-TEST-01's conflict check covers it as it covers the other modes.
- AC-TEST-18 (M5.3): A selection never clears Boot's `spring.http.serviceclient.<id>.base-url`: under an `external`
  selection a host value that differs from the mock URL fails with TL0003 naming both keys (AC-EXT-25), and under a
  `loopback` selection any host value fails with TL0003 naming the key and the fix (remove it, R-MODE-06).

### R-TEST-02 — External dependency test wiring

`topolith-spring-test` provides mock-server wiring for external dependencies. Implements INV-6, P-2, D-18 and D-62.

- AC-TEST-03 (M3.4): An external contract call reaches a test-configured mock server through the helper. A
  context-owned mock server's stubs and recorded requests are cleared before and after each test method (as Spring
  Boot's `MockReset` does for mock beans), so tests sharing a cached context never see each other's stubs or calls. The
  mock serves but never records the readiness check's `GET /<id>/.topolith/api-version` (D-65), so `requests()` holds
  only the test's own calls whenever the check arrives (at ready or after a refresh, D-50). `@BeforeEach` stubs survive (the reset runs before them); `@BeforeAll`
  stubs do not. `reset()` leaves no built-in endpoint to lose: an unstubbed path, including
  `/<id>/.topolith/api-version`, answers 404 before and after a reset. The listener creates no lazy bean.
- AC-TEST-04 (M3.4): A mock-server error exercises the external client's API exception mapping.
  `problem(method, path, type, message)` writes the status the provider writes (merged `@ResponseStatus`, else 500; a
  non-error status becomes 500); the explicit-status overload rejects a status outside 400-599, or one that differs from a
  `@ResponseStatus`-derived status, with `IllegalArgumentException`; both overloads reject `ModuleUnavailableException`
  the same way; `unavailable(method, path, downstreamModuleId)` writes the provider's 503 with its fixed
  detail and the downstream module id. Status, content type and the `type`, `status`, `detail`, `exception` and
  `module` members equal `ModuleExceptionResolver`'s output for a declared exception with a `@ResponseStatus`, one
  without, and `ModuleUnavailableException`. The mock writes the exception class it is given, so pass the declared
  contract type.

### R-TEST-03 — Reference topology tests

The same payment tests exercise the monolith, hybrid and `none` hosts; the `none` case checks absent contributions.
Implements INV-2 and INV-6.

- AC-TEST-05 (M4.1): The same payment test cases pass in monolith, hybrid and `none` host configurations.
- AC-TEST-06 (M4.1): The `none` host has no disabled-module beans, routes, tables or auto-configurations.
- AC-TEST-07 (M4.2): Hybrid end-to-end tests exercise both a fixed URL and Consul discovery with Testcontainers.

### R-TEST-04 — By-value proxy

`topolith-spring-test` can put a by-value proxy in front of an internal module's contracts, so an internal call passes
its arguments and result by value as an HTTP call does (INV-6), and a mutation that would leak between caller and
provider in internal mode fails the test instead of passing unseen (D-79). A test enables it with `@TopolithByValue` on
the test class (the module ids; no id means every internal module; inherited and found on enclosing classes like
`@TopolithModuleMode`) or with the builder's `byValue()` after `internal()`. Contract types the round trip cannot carry
(`Page`, `Stream`, entities) are R-ARCH-02's to reject statically; the proxy rejects them again at run time.
`@TopolithByValue` in a context with no JSON HTTP message converter fails context load with `IllegalStateException`,
and the proxy never wraps a mock (a `@MockitoBean` or `@MockitoSpyBean` contract bean).

- **Copies.** A call through an enabled module's contract bean serializes each argument with the JSON mapper of the
  host's HTTP message converters (the one the module's HTTP service group would use in external mode) and hands the
  provider the copy read back by the parameter's generic type; the result is serialized the same way and the caller gets
  the copy read back by the method's generic return type. `null` and `void` pass unchanged.
- **Detection.** The proxy fails with an `AssertionError` naming the module, the contract method, the argument index or
  the result, and the first JSON path that differs, when:
  - the provider has changed an argument: the copy it received serializes differently after the call, returned or
    thrown, than before it;
  - the provider changes an argument after the call returned (it kept the copy): each argument copy is kept with its
    serialized form after the call and serialized again at each later call to the module and at the end of the test
    method;
  - the provider changes an object after returning it: each returned object is kept with its serialized form and
    serialized again at each later call to the module and at the end of the test method;
  - an argument or result does not survive the round trip: serializing or reading it back fails, or the copy serializes
    differently from the original (a field lost or renamed on the way).

  A changed argument or a failed round trip is thrown at the call in place of its result, and a later change at the
  next call to the module. Every violation is also recorded, and the test execution listener fails the test method in
  `afterTestMethod` when one was recorded during it, so code under test that catches the error cannot hide it. Kept
  objects and records are released after each test method. A caller changing its own original argument, or its own
  copy of a result, is not a failure (D-79). A change made on another thread after the test method ended is not seen.
- **Exceptions.** A declared exception the provider throws (R-EXT-04) reaches the caller rebuilt as the caller's
  translation rebuilds it: the declared type through its public `(String)` or no-arg constructor, with the message and
  the original as suppressed. `ModuleUnavailableException` and undeclared exceptions pass as thrown (D-55).
- **Scope.** The proxy wraps only internal modules: an enabled module in `loopback` or `external` mode already crosses
  HTTP, and a `none` module has no calls, so none of them gets a proxy. An id that no descriptor names fails context
  load with `IllegalStateException` naming it. Without `@TopolithByValue` or `byValue()` there is no proxy and no
  recorded state, and the proxy does not change R-EXT-07's transaction boundary.

Implements INV-6, D-55, D-74 and D-79.

- AC-TEST-08 (M5.2): With the proxy on, a call to an internal module's contract hands the provider copies of its
  arguments and the caller a copy of the result, equal as JSON and read by generic type (a `List` of contract records
  included); without it, the provider receives the caller's own instances.
- AC-TEST-09 (M5.2): A provider that changes its argument during the call fails the call with an `AssertionError`
  naming the module, the method, the argument index and the changed path; a caller that changes its own original
  argument after the call does not fail.
- AC-TEST-10 (M5.2): A provider that changes an argument it kept, or an object it returned, after the call fails at the
  next call to the module and fails the test method through the listener, also when the code under test catches the
  error; nothing is kept past the test method.
- AC-TEST-11 (M5.2): An argument or result that does not survive the round trip (a `Page`, a type without a creator, a
  field lost on the way) fails the call with an `AssertionError` naming the type.
- AC-TEST-12 (M5.2): A declared exception reaches the caller as its declared type with its message;
  `ModuleUnavailableException` and an undeclared exception pass as thrown.
- AC-TEST-13 (M5.2): An unknown module id, or a context with no JSON HTTP message converter, fails context load with
  `IllegalStateException`; an external or `none` module and a mocked contract bean get no proxy, and a test without the
  annotation or `byValue()` has none.
- AC-TEST-17 (M5.3): A `loopback` module named by `@TopolithByValue` gets no proxy; its calls are copied by HTTP alone.

### R-TEST-05 — Mode matrix

A parity test is one test class run against a module in `internal`, `loopback` and `external` mode. In 0.1.0 the matrix
is a shared abstract test class with one concrete subclass per mode, each selecting the mode through R-TEST-01's helper
or its host's configuration; `topolith-spring-test` adds no matrix runner (D-80). The internal run enables the by-value
proxy for the module under test (R-TEST-04), the loopback run uses a listening server (`RANDOM_PORT`, R-MODE-06), and
the external run reaches a real provider or a `TopolithMockServer`. The samples run the shared payment tests of
`samples/payment-tests` (R-TEST-03) as the matrix: the monolith host with payment internal under the by-value proxy and
again with payment loopback, and the hybrid host with payment external against the payment service. The matrix checks
contract-call parity only: events, persistence and listeners behave as in `internal` mode, and event parity is RFC 0001
§7 (0.3) (Q-27). In a loopback run a test class must be outside the module's package: "inside the module" is the package
alone (package = module, by design), so a test class in the module's package or a subpackage fails with
TL0032, because it would get the provider and never cross HTTP (D-85). Implements INV-6, P-2, D-74, D-80 and D-85.

- AC-TEST-14 (M5.5): The shared payment test cases pass with payment internal under the by-value proxy, loopback, and
  external against the payment service, and each run asserts that payment runs in the mode it names.
- AC-TEST-19 (M5 gate): In a loopback context, a `@MockitoBean` and a `@MockitoSpyBean` of the module's contract in a test
  class outside the module's package apply to the module's group client and the context starts: a caller outside the
  module reaches the mock, or the spy, which still calls through HTTP, and the provider and its routes stay. A by-name
  override that names the provider's bean still targets the provider (D-84); it is not covered.
- AC-TEST-20 (M5 gate): A test class in a loopback module's package, a subpackage or a static nested class there fails
  with TL0032 naming the class and the module when its instance is prepared; a test class outside every module package, in a
  sibling package sharing the module's name prefix, or in an `internal` or `external` module's package does not.
- AC-TEST-15 (M5.5): A deliberately mutable payment fixture, whose provider changes an argument or a result it
  returned, fails the by-value run with R-TEST-04's `AssertionError`; a fixture contract returning `Page` fails both
  `TopolithArchitecture.verify()` (R-ARCH-02) and the by-value round trip.
