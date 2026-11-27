# 10 — Module descriptors

This file specifies module metadata, its generated and hand-written forms, and the module graph.
It routes work on `@TopolithModule`, `@Idempotent`, contract inference and API descriptors.

## Rules

### R-DESC-01 — Module declaration

An optional `@TopolithModule` (`io.github.rey5137.topolith.annotation`, in `topolith-core`, CLASS retention,
String-only members `id`, `api` (exact packages, empty by default), `requires`, `optional` and `provides`) on
`package-info.java` declares
the module id, API packages, required and optional dependencies; the module's root is the declaring package, recorded
as the descriptor's required `package` key (D-27, D-30). Adopters add `topolith-core` with `provided` scope.
Business code needs no Topolith base class or other Topolith type, and the annotation appears only in
`package-info.class`. A compilation holds at most one declaration (D-29). Implements INV-1, P-1, D-9 and D-29.

On a package outside its own `api` list the annotation is a module declaration. Each `api` package must exist, on the
classpath or in the compilation.

A module declaration with no `api` packages (D-115) is a module without contracts, such as a listener-only or job
module: it writes a module descriptor without `api-packages`, `provides` or `idempotent`, emits no API descriptor and
needs no API artifact; `api-version` is still required. Its mode is `internal` or `none`; `external` or `loopback`
fails startup with TL0003 (R-MODE-01). `provides` on such a declaration is a compile error.

- AC-DESC-17 (M8.2): A module declaration with `api = {}` (or no `api`) compiles to a module descriptor without
  `api-packages`, `provides` or `idempotent`, emits no API descriptor, and the descriptor parses and round-trips; an
  API declaration still needs at least one package.
- AC-DESC-18 (M8.2): A host runs a module without contracts `internal` (its beans and listeners active) and `none`
  (no trace of it, INV-2) with no API artifact on the classpath.

- AC-DESC-01 (M1.2): Processing an annotated package emits `META-INF/topolith/modules/<id>.properties`.
- AC-DESC-02 (M1.2): The emitted descriptor records the id, the declaring `package` (M1.3), API packages, `requires`
  and `optional` declarations.
- AC-DESC-03 (M1.2): A module's business classes work without importing a Topolith type.

### R-DESC-02 — Provided contracts and version

The processor infers `provides` from the concrete classes under the declaring package (api packages excluded) and the
interfaces they implement: the supertype closure, erased, keeping interfaces that are public, neither sealed nor
annotation types, and in exactly an api package (a member interface is written with its binary name,
`pkg.Outer$Inner`), then dropping any that is a superinterface of another one kept. Anonymous and local classes are
invisible. A concrete class outside the package that implements such an interface is a warning. The `api-version` is
the required processor option `-Atopolith.apiVersion=${project.version}`; its absence or a blank value is a compile
error citing TL0013 at the annotation, and descriptor problems the core builder reports (D-27) are compile errors,
never exceptions from the processor. The processor writes the lowest `format-version` that can express the
descriptor (D-39). Output is sorted and has no timestamp. The processor also records the `idempotent` methods of the
provided contracts (R-DESC-05, D-100). When the api packages are not in the
compilation the build reads the API declaration, the `@TopolithModule` in the package-info class of an api package on
the classpath (a JAR or a class directory), and fails if there is none or its `id` or `api` differ from the module's. It
recomputes the API's `provides` and `idempotent` from the classpath types of the api packages with the API
declaration's rule and fails if they differ from the inferred ones. This does not prove that the API artifact carries
its descriptor file.
The api packages are classified by the types they contain (compiled now, already in the output directory, or on the
classpath), and an api package with both compiled and classpath types, or a classpath declaration, fails as split.
Implementers are read from this compilation plus the declaring package's own output, so a partial compile (an IDE, or
incremental compilation off) that skips them needs a full rebuild, and a deleted implementer's stale descriptor is
cleared by a clean build.
When every api package is in the compilation it emits both descriptors after the same comparison. Implements INV-4,
INV-5, P-6, D-9 and D-29.

An interface annotated `@ConsumerImplemented` (`io.github.rey5137.topolith.contract`, `@Incubating`, RUNTIME, TYPE) is a
consumer-implemented SPI (D-110): it is not a contract candidate on either side, so neither the API's nor the module's
`provides` lists it, and a class implementing it is not its provider. Both sides first drop the marked interfaces from
the candidates and then keep the most-derived of the rest, so an unmarked supertype of a marked interface stays a
contract that the SPI's implementers provide, unless it is marked too. A contract provided only by a `@Bean` method (a
lambda or factory result) is listed in the module declaration's `provides` (fully qualified, binary names for member
interfaces): each entry must be in the API's inferred `provides` and must have no implementing class under the
package, or the build fails; the listed and inferred contracts together are the module's `provides`. `provides` on an
API declaration is a compile error. At startup (which reads the listed entries from the module's `package-info.class`,
D-128) an internal module needs exactly one bean of each listed contract among the definitions it contributes (owned by
its bean definition, as D-61 assigns ownership, not by its class): several fail
with TL0005, none with TL0044 (new, M8.3). R-EXT-07's boundary and R-EXT-11's observation apply to that bean whatever
its class, a JDK proxy or a factory result from outside the module's package included. For an exposed internal or
loopback module, Topolith registers routes for the listed contract's bean (R-EXT-05, D-125).

- AC-DESC-14 (M8.3): An api-package interface marked `@ConsumerImplemented` is left out of the API's and the module's
  `provides`, also when a class under the module's package implements it, and a module whose only implementation of a
  marked interface is such a class builds. For a marked `B extends A` with `A` unmarked, both the API and the module
  list `A` (the same set on both sides, no TL0022), and marking `A` too leaves both out.
- AC-DESC-15 (M8.3): A contract listed in the module declaration's `provides` and provided by a `@Bean` lambda is in
  the module descriptor, and an internal call to it works with R-EXT-07's boundary; an entry outside the API's
  `provides`, an entry with an implementing class, or `provides` on an API declaration fails the build. A listed
  contract provided by a `@Bean` method that returns an instance of a class outside the module's package is observed
  and runs under R-EXT-07's boundary. Moved to a module `@RestController` that implements it, the same contract is
  inferred, not listed, and works in loopback and external mode.
- AC-DESC-16 (M8.3): An internal module with no bean for a contract its declaration lists fails startup with TL0044
  (new, M8.3) naming the module and contract, and two beans fail with TL0005; a `none` module is not checked.
  Routes for listed contracts are covered by AC-EXT-80.

- AC-DESC-04 (M1.2): An implemented API interface is included in `provides`; an interface that is not a contract
  candidate (sealed or annotation) is not; a candidate no class implements fails the build.
- AC-DESC-05 (M1.2): The generated `api-version` equals the Maven project's version.

### R-DESC-03 — API artifact metadata

An `@TopolithModule` on a package that is in its own `api` list is an API declaration (D-29): it sets neither
`requires` nor `optional`, each `api` package holds a type in the compilation, and it emits
`META-INF/topolith/apis/<id>.properties` with id, API packages, `provides`, `idempotent` (R-DESC-05) and `api-version`.
Its `provides` are the public, non-sealed, non-annotation interfaces in the api packages that are not
`@ConsumerImplemented` (D-110), keeping only the most-derived. Implements INV-5, P-6, D-29 and D-110.

- AC-DESC-06 (M1.2): An API declaration emits a descriptor with all four fields for its module, and a module build
  fails when the classpath API declaration is missing or differs.

### R-DESC-04 — Graph and identity

The core model parses descriptors and resolves required and optional edges. Duplicate module ids fail with TL0006;
cycles in `requires` fail with TL0008. An absent optional dependency is not an unmet requirement. Module packages never nest, and no
other module's api package lies in or under a module's package; either fails with TL0017 in the identity stage (D-30).
Descriptors follow D-27, except that a module descriptor may omit `api-packages` (D-115), and failures are
`TopolithException`s (D-28). A required edge to a module with no descriptor is allowed: unmet
requirements are a query, and M1.3 raises TL0004 with mode context. Cycles are checked over every descriptor present,
whatever the modes. Discovery of descriptors on the classpath is not core's (M1.3). Implements INV-4.

- AC-DESC-07 (M1.1): Parsing preserves module identity, API metadata, required and optional dependency edges.
- AC-DESC-08 (M1.1): Duplicate ids fail with TL0006, and a required-edge cycle fails with TL0008.
- AC-DESC-09 (M1.1): An absent optional dependency does not make the graph fail an unmet-requirement check.
- AC-DESC-10 (M1.3): Two module packages that are equal or nested, or another module's api package equal to or under a
  module's package, fail with TL0017 naming both modules; a module's own api package under its own package is allowed.

### R-DESC-05 — Idempotency declaration

A contract method is recorded as idempotent in the module and API descriptors only when explicitly declared with
`@Idempotent` (`io.github.rey5137.topolith.contract.Idempotent`, in `topolith-core`, `@Incubating`, `@Documented`,
`RUNTIME` retention, targets `TYPE` and `METHOD`, no members); HTTP exchange annotations (`@GetExchange`,
`@PutExchange`, `@DeleteExchange`, `@HttpExchange`) never add a method to the descriptor's `idempotent` set, and the
caller-side `remote.retry.safe-methods-idempotent` setting (R-EXT-14) does not change the descriptor (D-100). Removing `@Idempotent` from a contract method or interface, or narrowing it
(for example by adding a non-idempotent overload), is a breaking contract change that needs an `api-version` major
bump; adding it is compatible (D-108).

- **Contract and method scope.** On a provided contract interface `C` (an entry of `provides`), a public, abstract
  (non-`default`, non-`static`, non-`private`) interface method of `C` (declared on `C` or inherited from a
  superinterface, `java.lang.Object` methods excluded) is idempotent when `C` carries `@Idempotent`, or when the method
  declaration `C` exposes (on `C` if `C` declares or overrides it, else on the superinterface from which `C` inherits
  it) or that declaration's enclosing interface carries `@Idempotent`. A `default` interface method is not an HTTP
  exchange and is never recorded in `idempotent`, even when its enclosing interface carries `@Idempotent`. When `C`
  overloads a method name, every abstract overload of that name on `C` must agree on whether it is idempotent.
- **Processor.** For both module and API declarations (R-DESC-02, R-DESC-03), the processor records each idempotent
  contract method as `<contract binary FQN>#<method name>`, sorted lexicographically, in the descriptor's `idempotent`
  key. `@Idempotent` on a non-interface, on a type outside the module's `api` packages, on a `default`, `static` or
  `private` interface method, or on one overload of a method name when another abstract overload of that name on the
  same contract is not idempotent fails compilation with TL0013. When the module build reads its `api` packages from
  the classpath or the same compilation, it recomputes `idempotent` from the API types and fails with TL0013 if the
  sets differ.
- **Descriptor format.** In `format-version=1`, `idempotent` is an optional comma-separated list in both
  `apis/<id>.properties` and `modules/<id>.properties`, exposed as `ApiDescriptor.idempotent()` and
  `ModuleDescriptor.idempotent()`; keeping `format-version=1` rather than bumping the format version (D-39) is allowed
  only because `0.1.0` is not yet released (D-100). An absent or empty `idempotent` key means no method of the module
  is declared `@Idempotent` (empty set), so descriptors written without `idempotent` remain readable and valid. Each
  entry has the form `<contract>#<method>`, where `<contract>` is listed in the descriptor's own `provides` and
  `<method>` is a valid Java identifier; a duplicate entry, a malformed entry, or a `<contract>` not in `provides`
  fails with TL0013 (D-27, D-100). `Descriptors.write` writes `idempotent` after `provides`.
- **Startup check.** For every `internal`, `loopback` or `external` module (`none` is not checked, INV-2), startup
  recomputes the idempotent methods of each loaded provided contract interface from its `@Idempotent` annotations and
  compares them against the module's effective descriptor (`modules/<id>.properties` for an internal or loopback
  module, `apis/<id>.properties` for an external module). Any difference, any difference between an internal or
  loopback module's module descriptor and its host API descriptor on `idempotent`, `@Idempotent` on a `default` method
  or disagreeing overloads of one method name on a loaded contract, or `@Idempotent` on a separate
  `remote.http-interface` (D-49) fails startup with TL0040 naming the module and contract (R-VAL-01, D-100).

Implements INV-1, INV-2, INV-4, INV-6, P-6, D-27, D-39, D-54 and D-100.

- AC-DESC-11 (M7.2): Parsing and writing a module or API descriptor round-trips its `idempotent` set of
  `<contract>#<method>` entries under `format-version=1`; a descriptor with a missing or empty `idempotent` key parses
  with an empty set; a duplicate entry, an entry not of the form `<contract>#<method>`, a `<contract>` not in the
  descriptor's `provides`, or a `<method>` that is not a Java identifier fails with TL0013.
- AC-DESC-12 (M7.2): `@Idempotent` on an abstract contract method, a contract interface or an inherited superinterface
  declaration emits the sorted `<contract>#<method>` entries in `idempotent` in both `apis/<id>.properties` and
  `modules/<id>.properties` (skipping `default` methods on a type-annotated interface); `@GetExchange`, `@PutExchange`
  and `@DeleteExchange` without `@Idempotent` emit none; `@Idempotent` on a concrete class, an interface outside
  `api-packages`, a `default`, `static` or `private` interface method, or one overload of a method name when another
  abstract overload on the same contract is not idempotent fails compilation with TL0013, as does a module build whose
  classpath API's idempotent methods differ.
- AC-DESC-13 (M7.2): At startup, for an `internal`, `loopback` or `external` module, a mismatch between the descriptor's
  `idempotent` set and the `@Idempotent` annotations on the loaded provided contract interfaces, a mismatch between an
  internal or loopback module's module and API descriptors on `idempotent`, `@Idempotent` on a `default` method or
  disagreeing overloads of one method name on a loaded contract, or `@Idempotent` on a separate
  `remote.http-interface` fails with TL0040; a `none` module is not checked.
