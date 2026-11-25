# 11 — Modes and activation

This file specifies mode resolution, inclusion-based activation, persistence filtering and refresh behavior.
It applies to every host and to each module it knows.

## Rules

### R-MODE-01 — Explicit modes

A host declares `internal`, `external` or `none` (trimmed, case-insensitive) in `topolith.modules.<id>.mode` for each
known module. A module is known when a module descriptor or an API descriptor carries its id (D-31). Only a standalone
`*-service` may default its own module to `internal`, by setting `topolith.standalone=<id>`; other missing modes, and
invalid values, fail with TL0001. A mode for an id with no descriptor fails with TL0016, or with TL0002 when it is
`internal`. Resolution is deterministic from configuration and cached as the `topolithTopology` bean. Implements INV-3,
INV-4, INV-9, P-3, D-5, D-8 and D-31.

- AC-MODE-01 (M1.3): Each explicit mode resolves to its declared value regardless of an implementation JAR's presence.
- AC-MODE-02 (M1.3): A host missing a known module's mode fails with TL0001 rather than activating it implicitly.
- AC-MODE-03 (M1.3): A standalone service defaults only its own module to `internal`.
- AC-MODE-21 (M1.3): A mode for an id with no module or API descriptor fails with TL0016, or with TL0002 when it is
  `internal`.
- AC-MODE-22 (M1.3): An invalid mode value fails with TL0001.

### R-MODE-02 — Inclusion-based activation

The host scans only its own package. `TopolithAutoConfiguration`, ordered first, imports a non-deferred
`TopolithModuleImportSelector` that returns the component-scan candidates under each internal module's `package`,
less every known api package and with Boot's `TypeExcludeFilter` and `AutoConfigurationExcludeFilter` applied, so
module beans exist before Boot's conditions of later auto-configurations are evaluated. Imported classes get
fully qualified default bean names. An `AutoConfigurationImportFilter` removes auto-configurations owned by
non-internal modules: a class under the module's `package`, or one listed by an `AutoConfiguration.imports` file when
the imports file or the class is in the classpath root that holds exactly that module's descriptor (conflicting roots
own nothing). A host component scan that covers a non-internal
module's package fails with TL0007. An internal module needs its implementation JAR (its module descriptor);
otherwise startup fails with TL0002. Implements INV-2, INV-8, P-3, D-7 and D-30.

- AC-MODE-04 (M1.3): An internal module's configuration and packages are imported when its implementation is present.
- AC-MODE-05 (M1.3): A missing internal implementation fails with TL0002 and a fix-it message.
- AC-MODE-06 (M1.3): A non-internal module contributes no component or owned auto-configuration, even if its JAR
  is present.
- AC-MODE-23 (M1.3): A non-internal module's auto-configuration outside its package, listed by an
  `AutoConfiguration.imports` file, is removed, also when the imports file and the class sit in separate roots.
- AC-MODE-24 (M1.3): A host component scan covering a non-internal module's package fails with TL0007.

### R-MODE-03 — Non-internal isolation and dependencies

Neither `external` nor `none` imports the module's implementation. External registers contract clients as specified
in R-EXT-01; `none` registers none. Only `internal` is checked for `requires`: an internal module's unmet
`requires` fails with TL0004. A required module is available when it is `internal` or `external`; `none` and unknown
are not. External modules' own dependencies are met remotely and are not checked. Implements INV-2, INV-4 and D-21.

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
