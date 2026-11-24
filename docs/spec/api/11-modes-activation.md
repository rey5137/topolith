# 11 — Modes and activation

This file specifies mode resolution, inclusion-based activation, persistence filtering and refresh behavior.
It applies to every host and to each module it knows.

## Rules

### R-MODE-01 — Explicit modes

A host declares `internal`, `external` or `none` for each known module. Only a standalone `*-service` may default
its own module to `internal`; other missing modes fail with TL0001. Resolution is deterministic from configuration.
Implements INV-3, INV-4, INV-9, P-3, D-5 and D-8.

- AC-MODE-01 (M1.3): Each explicit mode resolves to its declared value regardless of an implementation JAR's presence.
- AC-MODE-02 (M1.3): A host missing a known module's mode fails with TL0001 rather than activating it implicitly.
- AC-MODE-03 (M1.3): A standalone service defaults only its own module to `internal`.

### R-MODE-02 — Inclusion-based activation

The host scans only its own package. `TopolithImportRegistrar` imports internal modules, and an
`AutoConfigurationImportFilter` removes auto-configurations owned by non-internal modules. An internal module needs
its implementation JAR; otherwise startup fails with TL0002. Implements INV-2, INV-8, P-3 and D-7.

- AC-MODE-04 (M1.3): An internal module's configuration and packages are imported when its implementation is present.
- AC-MODE-05 (M1.3): A missing internal implementation fails with TL0002 and a fix-it message.
- AC-MODE-06 (M1.3): A non-internal module contributes no component or owned auto-configuration, even if its JAR
  is present.

### R-MODE-03 — Non-internal isolation and dependencies

Neither `external` nor `none` imports the module's implementation. External registers contract clients as specified
in R-EXT-01; `none` registers none. Only `internal` is active for `requires` checks: an internal module's unmet
`requires` fails with TL0004. External modules' dependencies are met remotely. Implements INV-2, INV-4 and D-21.

- AC-MODE-07 (M1.3): A `none` module contributes no beans, routes, listeners or scheduled tasks.
- AC-MODE-08 (M1.3): An active module requiring a `none` module fails with TL0004 and names the missing requirement.
- AC-MODE-09 (M2.1): An external module contributes no implementation beans or routes while its contract client
  is usable.
- AC-MODE-16 (M1.3): External payment requiring user starts with user set to `none`.

### R-MODE-04 — Persistence and migrations

Use filtered `PersistenceManagedTypes`, per-module repository registration and `db/migration/<id>` locations.
Module-owned DataSource and entity-manager configuration remains usable. Implements INV-2 and D-7.

- AC-MODE-10 (M1.4): Internal modules register only their own managed entities, repositories and migration locations.
- AC-MODE-11 (M1.4): A non-internal module leaves no entity, repository, migration or table, even with its JAR present.
- AC-MODE-12 (M1.4): An internal module's own DataSource and entity-manager configuration works.

### R-MODE-05 — Refresh does not change topology

With `topolith-spring-cloud`, an `EnvironmentChangeEvent` changing a module's mode logs TL0010 naming its running
and requested modes, and the actuator reports restart pending. The running topology remains unchanged. Mode-related
restart pending is computed and clears when every requested mode again matches its running mode; an independent
URL/service-id wiring change can still require restart. Without Spring Cloud on the classpath there is no refresh
handling. `topolith-spring-boot` detects Spring Cloud context classes by name, without importing them. When they are
present but `topolith-spring-cloud` is absent, it logs startup warning TL0014 advising the user to add that module
for refresh handling. Implements INV-4, INV-7, INV-9, D-11, D-13, D-20 and D-23.

- AC-MODE-13 (M2.6): A changed mode logs TL0010 with module id and both modes and reports restart pending.
- AC-MODE-14 (M2.6): Refreshing a changed mode does not alter active module contributions or running mode.
- AC-MODE-15 (M2.6): A refresh changing several modes identifies each changed module and both of its modes.
- AC-MODE-17 (M2.6): Mode-related restart pending clears only when all requested modes again match running modes.
- AC-MODE-18 (M2.6): Startup with Cloud context but without `topolith-spring-cloud` logs warning TL0014 and its hint.
- AC-MODE-19 (M2.6): Startup without Cloud context classes does not log TL0014.
- AC-MODE-20 (M2.6): Cloud context classes with `topolith-spring-cloud` present do not log TL0014.
