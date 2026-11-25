# 10 — Module descriptors

This file specifies module metadata, its generated and hand-written forms, and the module graph.
It routes work on `@TopolithModule`, contract inference and API descriptors.

## Rules

### R-DESC-01 — Module declaration

An optional `@TopolithModule` (`io.github.rey5137.topolith.annotation`, in `topolith-core`, CLASS retention,
String-only members `id`, `api` (required, exact packages), `requires` and `optional`) on `package-info.java` declares
the module id, API packages, required and optional dependencies; the module's root is the declaring package, recorded
as the descriptor's required `package` key (D-27, D-30). Adopters add `topolith-core` with `provided` scope.
Business code needs no Topolith base class or other Topolith type, and the annotation appears only in
`package-info.class`. A compilation holds at most one declaration (D-29). Implements INV-1, P-1, D-9 and D-29.

On a package outside its own `api` list the annotation is a module declaration. Each `api` package must exist, on the
classpath or in the compilation.

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
never exceptions from the processor. Output is sorted and has no timestamp. When the api packages are not in the
compilation the build reads the API declaration, the `@TopolithModule` in the package-info class of an api package on
the classpath (a JAR or a class directory), and fails if there is none or its `id` or `api` differ from the module's. It
recomputes the API's `provides` from the classpath types of the api packages with the API declaration's rule and fails
if they differ from the inferred ones. This does not prove that the API artifact carries its descriptor file.
The api packages are classified by the types they contain (compiled now, already in the output directory, or on the
classpath), and an api package with both compiled and classpath types, or a classpath declaration, fails as split.
Implementers are read from this compilation plus the declaring package's own output, so a partial compile (an IDE, or
incremental compilation off) that skips them needs a full rebuild, and a deleted implementer's stale descriptor is
cleared by a clean build.
When every api package is in the compilation it emits both descriptors after the same comparison. Implements INV-4,
INV-5, P-6, D-9 and D-29.

- AC-DESC-04 (M1.2): An implemented API interface is included in `provides`; an interface that is not a contract
  candidate (sealed or annotation) is not; a candidate no class implements fails the build.
- AC-DESC-05 (M1.2): The generated `api-version` equals the Maven project's version.

### R-DESC-03 — API artifact metadata

An `@TopolithModule` on a package that is in its own `api` list is an API declaration (D-29): it sets neither
`requires` nor `optional`, each `api` package holds a type in the compilation, and it emits
`META-INF/topolith/apis/<id>.properties` with id, API packages, `provides` and `api-version`. Its `provides` are the
public, non-sealed, non-annotation interfaces in the api packages, keeping only the most-derived. Implements INV-5, P-6
and D-29.

- AC-DESC-06 (M1.2): An API declaration emits a descriptor with all four fields for its module, and a module build
  fails when the classpath API declaration is missing or differs.

### R-DESC-04 — Graph and identity

The core model parses descriptors and resolves required and optional edges. Duplicate module ids fail with TL0006;
cycles in `requires` fail with TL0008. An absent optional dependency is not an unmet requirement. Module packages never nest, and no
other module's api package lies in or under a module's package; either fails with TL0017 in the identity stage (D-30).
Descriptors follow D-27 and failures are `TopolithException`s (D-28). A required edge to a module with no descriptor is allowed: unmet
requirements are a query, and M1.3 raises TL0004 with mode context. Cycles are checked over every descriptor present,
whatever the modes. Discovery of descriptors on the classpath is not core's (M1.3). Implements INV-4.

- AC-DESC-07 (M1.1): Parsing preserves module identity, API metadata, required and optional dependency edges.
- AC-DESC-08 (M1.1): Duplicate ids fail with TL0006, and a required-edge cycle fails with TL0008.
- AC-DESC-09 (M1.1): An absent optional dependency does not make the graph fail an unmet-requirement check.
- AC-DESC-10 (M1.3): Two module packages that are equal or nested, or another module's api package equal to or under a
  module's package, fail with TL0017 naming both modules; a module's own api package under its own package is allowed.
