# 61 — Build, dependencies and release

This file specifies the 0.1 reactor, dependency boundaries, reference adopter and release artifacts.
It routes build, layering, documentation and publishing work.

## Rules

### R-BUILD-01 — Reactor and platform

The 0.1 reactor contains core, Spring Boot integration, optional `topolith-spring-cloud`, processor,
`topolith-spring-test`, a BOM and samples. Use Java 21, Spring Boot 4.2 and Spring Framework 7; Spring Cloud 2026.0
is used only for discovery and refresh in the optional Cloud module. Implements INV-7, D-10, D-18 and D-20.

- AC-BUILD-01 (M0.2): A clean checkout builds core, Spring Boot, optional Cloud, processor, Spring test and samples;
  the BOM includes the Cloud integration.
- AC-BUILD-02 (M0.2): The platform selects the Java and Spring baseline and supplies Topolith BOM entries. The BOM
  also imports `spring-boot-dependencies` and `spring-cloud-dependencies`, so a host importing only it gets Boot,
  Cloud and Topolith versions (D-68).

### R-BUILD-02 — Dependency boundaries

`topolith-core` imports only the JDK; Spring imports live only in `topolith-spring-*` modules. Spring Cloud and
Spring Modulith live only in optional modules, behind `@ConditionalOnClass`. Modules depend on other business modules
only through `*-api`. Implements INV-5 and INV-7.

- AC-BUILD-03 (M0.3): A deliberately bad fixture proves a non-JDK core dependency fails the ArchUnit layering rule.
- AC-BUILD-04 (M0.3): A deliberately bad fixture proves Spring outside `topolith-spring-*` fails the layering rule.
- AC-BUILD-05 (M0.3): Bad fixtures prove Cloud and Modulith dependencies outside optional modules fail layering.
- AC-BUILD-06 (M0.3): Optional Cloud and Modulith integrations are guarded by `@ConditionalOnClass`.
- AC-BUILD-07 (M3.1): A module depending on another module's implementation instead of its API fails boundary checks.

### R-BUILD-03 — Reference adopter

Samples contain user and payment API, module and service artifacts, plus monolith, hybrid and `none` hosts.
Implements INV-2, INV-3 and INV-5.

- AC-BUILD-08 (M4.1): Both sample modules have API, module and standalone service artifacts.

The shared topology tests are AC-TEST-05 and AC-TEST-06.

### R-BUILD-04 — Bundling

A normal Spring Boot host can depend on modules from different repositories and imports the platform BOM. Host builds
enforce Maven `dependencyConvergence` and `requireUpperBoundDeps`; CI rebuilds hosts against latest module versions.
Implements INV-7 and D-10.

- AC-BUILD-09 (M4.1): A sample host imports the platform and enforces both named Maven dependency rules.
- AC-BUILD-10 (M4.2): The latest-modules CI job rebuilds each host after a module publication.

### R-BUILD-05 — User documentation

Provide getting-started, legacy adoption checklist and configuration reference documentation; examples match tests.
The checklist describes the seven steps in RFC 0001 §4.3. The pages form a MkDocs site under `docs/site/`, built in
strict mode on every pull request and published to GitHub Pages from `main` (D-73). Implements P-5, D-6 and D-73.

- AC-BUILD-11 (M4.3): Getting-started, all seven legacy adoption steps and configuration reference are present.
- AC-BUILD-12 (M4.3): Each documentation snippet matches a test.
- AC-BUILD-16 (M4.5): The documentation site builds in strict mode, includes every user guide page and diagnostic code,
  and deploys from `main`.

### R-BUILD-06 — Publication and compatibility

Publish Apache-2.0 artifacts as `io.github.rey5137:topolith-*` to Maven Central. Public types are `@Incubating`
until 1.0. A release has a changelog and signed artifacts. Implements D-1.

- AC-BUILD-13 (M4.4): A release dry run produces signed artifacts with the specified coordinates and license.
- AC-BUILD-14 (M4.4): The release workflow targets Maven Central and includes the `0.1.0` changelog.
- AC-BUILD-15 (M4.4): Public types carry `@Incubating` before 1.0.

### R-BUILD-07 — Invariant changes

An architectural invariant is changed only through an accepted RFC. Implements INV-10 and the invariant rule in
`SPEC.md` §2. This process rule has no automated AC.
