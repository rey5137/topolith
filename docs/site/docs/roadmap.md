# Roadmap

## What 0.1 ships

- Modules: core, Spring Boot, optional Spring Cloud, the annotation processor, Spring test support and the BOM.
- Modes `internal`, `external` and `none` with inclusion-based activation and persistence isolation.
- External mode with a fixed `url` or a `service-id`, mandatory timeouts, ProblemDetail errors and live endpoint
  refresh.
- Startup validation with `TLnnnn` diagnostics, API-version readiness warnings and the `topolith` actuator endpoint.
- Descriptor-driven ArchUnit rules, a frozen baseline and the startup ownership check.
- The transaction boundary for internal contract calls.
- A reference adopter in `samples/` whose monolith, hybrid and `none` hosts pass the same payment tests.

## What comes after

| Release | Scope |
|---|---|
| 0.2 | Parity machinery: a `loopback` mode, a by-value test proxy, fault injection, a mode matrix and shared observation tags. |
| 0.3 | Mode-aware events: an externalization SPI and a Spring Modulith adapter. |
| 0.4 | Topology as configuration (`topology.yml`) and an extraction report that shows what would break if a module were split out, and Helm and Kubernetes generation. |
| 0.5 | Legacy adoption tooling: an adopt goal for the checklist, per-module configuration files and data-ownership checks. |
| Later | A Spring Modulith bridge, graph docs coloured by mode, an API compatibility gate, a local-development profile for hybrid setups and a Backstage catalog export and a Consul registrar SPI. |

## Not in 0.1

- The Maven goal that verifies a hand-written descriptor against bytecode. Startup still validates descriptors.
- Native images and the build-time topology guard.

## Tested environments

Tests start the packaged monolith JAR and its extracted layout, and exercise split Gradle-style class and resource
directories and a restart-style child class loader. A real Gradle build, WAR packaging, DevTools restart loader,
JVM AOT processing and AspectJ load-time weaving are deferred. Other deferred integration cases are recorded in
decision D-132. With a hand-written Gradle descriptor in resources, startup scans no module classes; ArchUnit still
checks the build.

## Not planned

- Changing the topology at runtime without a redeploy.
- In-JVM isolation of unmodified legacy services.
- A built-in saga engine.
