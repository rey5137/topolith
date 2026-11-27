# 11 — Modes and activation

This file specifies mode resolution, inclusion-based activation, persistence filtering and refresh behavior.
It applies to every host and to each module it knows.

## Rules

### R-MODE-01 — Explicit modes

A host declares `internal`, `external`, `loopback` or `none` (trimmed, case-insensitive) in `topolith.modules.<id>.mode`
for each known module. A module is known when a module descriptor or an API descriptor carries its id (D-31). Only a
standalone `*-service` may default its own module to `internal`, by setting `topolith.standalone=<id>`; other missing
modes, and invalid values, fail with TL0001. A mode for an id with no descriptor fails with TL0016, or with TL0002 when
it is `internal` or `loopback`. Resolution is deterministic from configuration and cached as the `topolithTopology`
bean. A module whose descriptor has no `api-packages` (R-DESC-01, D-115) has no contracts to call, so `external` or
`loopback` for it fails with TL0003. Implements INV-3, INV-4, INV-9, P-3, D-5, D-8, D-31 and D-115.

- AC-MODE-01 (M1.3): Each explicit mode resolves to its declared value regardless of an implementation JAR's presence.
- AC-MODE-02 (M1.3): A host missing a known module's mode fails with TL0001 rather than activating it implicitly.
- AC-MODE-03 (M1.3): A standalone service defaults only its own module to `internal`.
- AC-MODE-21 (M1.3): A mode for an id with no module or API descriptor fails with TL0016, or with TL0002 when it is
  `internal`.
- AC-MODE-22 (M1.3): An invalid mode value fails with TL0001.
- AC-MODE-39 (M5.3): `loopback` (trimmed, case-insensitive) resolves to the loopback mode, which `Topology.mode(id)`
  reports; with no module descriptor it fails with TL0002, as `internal` does.
- AC-MODE-62 (M8.2): `external` or `loopback` for a module without api packages fails with TL0003 naming the module
  and the fix (`internal` or `none`).

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
otherwise startup fails with TL0002. A `loopback` module is internal for activation (R-MODE-06).

The import filter runs even when Topolith's own auto-configuration does not. An `AutoConfigurationImportListener`
registered in `META-INF/spring.factories` therefore reads the exclusions of each `@EnableAutoConfiguration` import
(`spring.autoconfigure.exclude`, `exclude` and `excludeName`): an excluded auto-configuration that activates modules
(`TopolithAutoConfiguration` and the `Jpa`, `JpaRepositories`, `JdbcRepositories`, `Flyway` and `Liquibase` `*ModulesAutoConfiguration` classes of `topolith-spring-boot`) while the resolved topology has an `internal` or `loopback` module fails startup with TL0042 (new, M8.2)
naming the class (D-111); excluding one that activates no module, such as `topolith-spring-cloud`'s resilience
configuration, is not checked (TL0041 covers it). A test slice that never includes Topolith's auto-configuration excludes nothing and is
not checked. Implements INV-2, INV-4, INV-8, P-3, D-7, D-30 and D-111.

- AC-MODE-04 (M1.3): An internal module's configuration and packages are imported when its implementation is present.
- AC-MODE-05 (M1.3): A missing internal implementation fails with TL0002 and a fix-it message.
- AC-MODE-06 (M1.3): A non-internal module contributes no component or owned auto-configuration, even if its JAR
  is present.
- AC-MODE-23 (M1.3): A non-internal module's auto-configuration outside its package, listed by an
  `AutoConfiguration.imports` file, is removed, also when the imports file and the class sit in separate roots.
- AC-MODE-24 (M1.3): A host component scan covering a non-internal module's package fails with TL0007.
- AC-MODE-55 (M8.2): Excluding `TopolithAutoConfiguration`, or another Topolith auto-configuration that activates modules, by property or by
  `@SpringBootApplication(exclude)` while a module is `internal` or `loopback` fails startup with TL0042 (new, M8.2)
  naming the class; the same exclusion with only `none` modules, and a `@WebMvcTest` slice of the host and an excluded auto-configuration that activates no module start.

### R-MODE-03 — Non-internal isolation and dependencies

Neither `external` nor `none` imports the module's implementation. External registers contract clients as specified in
R-EXT-01; `none` registers none. Only `internal` and `loopback` are checked for `requires`: such a module's unmet
`requires` fails with TL0004. A required module is available when it is `internal`, `loopback` or `external`; `none` and
unknown are not. External modules' own dependencies are met remotely and are not checked. Implements INV-2, INV-4 and
D-21.

- AC-MODE-07 (M1.3): A `none` module contributes no beans, routes, listeners or scheduled tasks.
- AC-MODE-08 (M1.3): An active module requiring a `none` module fails with TL0004 and names the missing requirement.
- AC-MODE-09 (M2.1): An external module contributes no implementation beans or routes while its contract client
  is usable.
- AC-MODE-16 (M1.3): External payment requiring user starts with user set to `none`.

### R-MODE-04 — Persistence and migrations

Internal modules use the host's JPA and Spring Data JDBC by default. Topolith declares the
`persistenceManagedTypes` bean from the host's entity-scan or auto-configuration packages plus each internal shared-persistence module's `package`, less api packages,
and backs off for a host-declared one. A registrar ordered after Boot's repository auto-configuration registers each
internal module's JPA and JDBC repositories under fully qualified bean names, and restores Boot's scan of the host's
packages when only module registrations made Boot back off. With multiple stores, Spring Data's strict store selection
applies and an unassigned repository fails with TL0024. JDBC registration requires Boot's JDBC repository infrastructure; when it is unavailable, an uncovered repository fails with TL0024. Migrations live at `classpath:db/modules/<id>`. Each internal module migrates its own `db/modules/<id>` with Flyway
or Liquibase and its own tracking tables, after the host's migrations (its Flyway, its Liquibase and Boot's SQL-script initializers) and those of the modules
it requires. The host's
migration locations and changelog never reach a module's files.

A module that contributes an `EntityManagerFactory` owns its JPA entities and repositories; one that contributes a
`DataSource` owns its JDBC repositories and migrations. Such beans, `SpringLiquibase` included, must not be default candidates (`@Bean(defaultCandidate = false)` with
`@Qualifier`), unless the module is the `topolith.standalone` module. Host auto-configuration and entity-scan packages
must not overlap a module package, except that the `topolith.standalone` module's own package may (the module is the
application, and Topolith then adds nothing the host's scans already cover). JPA, Spring Data, Flyway and Liquibase support is guarded by `@ConditionalOnClass`, so a host
without those libraries starts unchanged. Implements INV-2, INV-4, INV-9, D-7, D-30 and D-32.

- **Module DataSource migrations (D-112).** A module that contributes exactly one DataSource and has scripts under
  `db/modules/<id>` but no Flyway, `FlywayMigrationInitializer` or `SpringLiquibase` of its own gets Topolith's module
  runner on that DataSource, with the same tool detection, tracking tables, ordering after the modules it requires and
  limits as on the host's DataSource. A runner the module declares still owns its migrations, and Topolith adds none.
  A module with several DataSources of its own and no runner fails with TL0020.
- **Other stores (D-113).** Topolith also registers each internal and loopback module's Spring Data R2DBC and MongoDB
  repositories and MyBatis mappers, as it does JPA and JDBC: one auto-configuration per store, each
  `@ConditionalOnClass` on its store, scanning only the module's `package`, backing off for the module's own
  `@Enable<Store>Repositories` or `@MapperScan` (as AC-MODE-35 does for JDBC), and restoring Boot's scan of the host's
  packages when only module registrations made it back off. Mappers bind to the host's single `SqlSessionFactory`; with
  several and none of them the module's own, an uncovered mapper fails with TL0024. With multiple stores Spring Data's
  strict store selection still applies.
- **MyBatis mapper locations (D-114).** The host's `mybatis.mapper-locations` are resolved at startup, and a resource
  owned by a module (D-61's resource ownership) fails startup with TL0043 (new, M8.2) naming the location and the
  resource, whatever the module's mode, as TL0020 does for a host migration location. A module's mapper XML lives beside
  its mapper interface, where MyBatis loads it.

- AC-MODE-10 (M1.4): Internal modules register only their own managed entities, repositories and migration locations.
- AC-MODE-11 (M1.4): A non-internal module leaves no entity, repository, migration or table, even with its JAR present.
- AC-MODE-12 (M1.4): An internal module's own DataSource and entity-manager configuration works.
- AC-MODE-25 (M1.4): Host auto-configuration or entity-scan packages that overlap a module's package fail with TL0018,
  except the standalone module's own, which then registers once.
- AC-MODE-26 (M1.4): An internal module's default-candidate DataSource, entity-manager factory, transaction manager,
  Flyway, Liquibase or `AbstractJdbcConfiguration` bean fails with TL0019, except in the `topolith.standalone` module.
- AC-MODE-27 (M1.4): A module migration that would not run exactly once fails with TL0020.
- AC-MODE-28 (M1.4): An internal module's entity managed by no entity-manager factory, or by several, fails with TL0021.
- AC-MODE-29 (M1.4): A module's migrations run once across topologies: the second run, standalone after composed or
  composed after standalone, finds nothing pending.
- AC-MODE-30 (M1 gate, D-36): In a topology with at least one module, a Spring Data repository or MyBatis mapper
  interface in an internal module's package or the host's auto-configuration packages that no registration covers fails
  with TL0024. A registered one, one of a `none` or `external` module, and a module-less topology do not.
- AC-MODE-31 (M1 gate, D-37): `topolith.standalone` with another module that has a descriptor and is `internal` fails
  with TL0025; with the others `external` or `none` it starts.

- AC-MODE-32 (M1.6): An internal module's JDBC repository is registered once under its fully qualified name and works
  against H2; if Boot's JDBC repository infrastructure is unavailable, it remains unregistered and fails with TL0024.
- AC-MODE-33 (M1.6): A `none` or `external` module leaves no JDBC repository definition and no module class loaded.
- AC-MODE-34 (M1.6): A host JDBC repository scan that registers a `none` module's repository fails with TL0007.
- AC-MODE-35 (M1.6): A Pattern A module's own JDBC repositories are not registered again, and the host's JDBC
  repositories remain registered.
- AC-MODE-36 (M1.6): A standalone module's JDBC repository is registered once when the host scan overlaps it.
- AC-MODE-37 (M1.6): An internal module's default-candidate `AbstractJdbcConfiguration` fails with TL0019.
- AC-MODE-38 (M1.6): With JPA and JDBC present, repositories assignable to each store register with that store;
  an interface neither store can assign fails with TL0024, and a JDBC factory-bean registration covers its interface.
- AC-MODE-56 (M8.3): An internal module with its own non-default-candidate DataSource, Flyway or Liquibase scripts
  under `db/modules/<id>` and no runner of its own has them run once on its DataSource, with its own tracking tables,
  and none on the host's; a second start finds nothing pending.
- AC-MODE-57 (M8.3): A module that declares its own runner gets no Topolith runner; a module with two DataSources of
  its own and no runner fails with TL0020; a `none` module's scripts run on no DataSource.
- AC-MODE-58 (M8.4): An internal module's MyBatis mapper is registered once under its fully qualified name against the
  host's `SqlSessionFactory` and its XML beside the interface is loaded; with two host factories and none the module's
  own it fails with TL0024; a module's own `@MapperScan` is not registered again.
- AC-MODE-59 (M8.4): An internal module's Spring Data MongoDB and R2DBC repositories are each registered once under
  their fully qualified names (bean definitions, no server), a module's own `@Enable<Store>Repositories` is not
  registered again, and the host's repositories of that store remain registered.
- AC-MODE-60 (M8.4): A `none` or `external` module leaves no MyBatis mapper, MongoDB or R2DBC repository definition and
  no module class loaded, and a host without each store's classes loads none of its registration.
- AC-MODE-61 (M8.2): A host `mybatis.mapper-locations` pattern that reaches a module's mapper XML fails startup with
  TL0043 (new, M8.2) in every mode, `internal` and `none` included; a pattern that reaches only the host's XML starts; the standalone module's own XML is the host's and is exempt.

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

### R-MODE-06 — Loopback mode

`loopback` runs a module in-process while every call to its contracts crosses real HTTP on the host's own server, for
tests and development. A loopback module is internal for activation: R-MODE-02, R-MODE-03's `requires` check,
R-MODE-04, the boundary rules and every startup check and diagnostic that names an internal module apply to it
unchanged, and INV-2's non-internal module is one that is neither `internal` nor `loopback` (D-75). It differs from
`internal` only in how other code reaches its contracts:

- **Callers.** Topolith registers one HTTP service group named `<id>` for the module's provided contracts, as R-EXT-01
  does for an external module. The provider bean stays a default candidate; Topolith installs a delegating
  `AutowireCandidateResolver`, only when a loopback module exists, that excludes the provider from a dependency typed
  as one of the module's provided contracts at an injection point outside the module's `package`, and the group's
  client from such a dependency at an injection point inside it (D-81). Code outside the module, the host included,
  therefore gets the HTTP client for the contract, while the module's own injections
  of its contract, any injection of the provider's concrete class and collections of its other interfaces still get
  the provider. The provider's routes are mapped, R-EXT-07's boundary applies to it, and it is not a second provider
  for TL0005. Nothing is marked `@Primary` and nothing falls back to the bean (INV-4). The resolver covers injection
  points only (D-84): a by-type programmatic lookup of the contract (`getBean(Class)`, `getBeanProvider(Class)`,
  `getBeansOfType`) is ambiguous by design, so code outside the module injects the contract or looks the client up as
  bean `<id>#<interface FQN>`; a lookup by the provider's bean name still returns the provider. A provider that is
  `@Primary` or whose class carries `@jakarta.annotation.Priority` would win such a lookup, so it fails with TL0003.
  "Inside the module" is the package alone, so production code in the module's package gets the provider (package =
  module, by design); a test class there fails with TL0032 (D-85, R-TEST-05).
- **Scope of parity.** Loopback checks contract-call parity only. Events, persistence and listeners behave as in
  `internal` mode in 0.1. When mode-aware events land (RFC 0001 §7, 0.3), a loopback module's cross-module events go
  through the externalization adapter as an external module's do, and without an adapter startup fails as for an
  external module (D-120, decision only in 0.1).
- **Routes.** The module's routes are mapped under its fixed `/<id>` prefix, as for an exposed module (R-EXT-05), and
  belong to `ExposedEndpoints` and `ExposedRequestMatcher`, so the host's `SecurityFilterChain` sees them and applies to
  loopback calls as to any other request. `expose.enabled` unset or `true` changes nothing; an explicit `false`
  contradicts the mode and fails with TL0003.
- **Base URL.** The client's base URL is `http://<host>:<port><context-path><servlet-path>/<id>`: `<port>` is the
  embedded servlet web server's actual local port, read once the server has started; `<host>` is `server.address` (an
  IPv6 address in brackets), or `localhost` when it is unset or a wildcard address; `<context-path>` is
  `server.servlet.context-path`; `<servlet-path>` is the path of Boot's resolved `DispatcherServletPath`
  (`spring.mvc.servlet.path`), empty when it is `/` or Boot registers none (D-82).
  A call made before the server listens fails as a call to a remote that is not listening does: it raises
  `ModuleUnavailableException` with kind `connection failure`. A loopback module needs a servlet web server listening
  on a port: a non-web application, a context that is not a `WebServerApplicationContext` (a `@SpringBootTest` in the
  default `MOCK` web environment), `server.port=-1` (no listening connector) or a server that uses SSL by Boot's
  resolved setting (`server.ssl.bundle` included) fails with TL0026, and a reactive application fails with TL0028
  (D-76).
- **Settings.** A non-blank `remote.url` or `remote.service-id` and an explicit
  `spring.http.serviceclient.<id>.base-url` contradict the mode and fail with TL0003, whose message names the key and
  the fix (remove it); a blank `remote.*` value counts as unset. A host HTTP service group named `<id>` that holds an
  interface that is not the module's fails with TL0003, as for an external module (R-EXT-02). `remote.connect-timeout`
  and `remote.read-timeout` (R-EXT-03), `remote.http-interface` (D-49), `ModuleClients.configure` (R-EXT-06) and the
  `loopback.*` keys (R-EXT-10) apply. R-VAL-04 compares the host API descriptor with the module's own descriptor, as for
  an internal module, and reads no remote version. Topolith maps the placeholder base URL `http://localhost/<id>` to
  the group (AC-MODE-53); it is not a user setting.
- **Refresh.** A mode change to or from `loopback` is a mode change under R-MODE-05 (TL0010, restart pending). A
  loopback module's client settings (its timeouts, `remote.http-interface`, `loopback.*`, and a non-blank
  `remote.url` or `remote.service-id`, which the next start rejects with TL0003) are restart-only: a refresh
  changing one leaves the client unchanged, logs TL0010 naming the module and the key, and marks restart pending until
  every such key again matches its running value (D-77).
- **Where.** Loopback is allowed in any host. It is chosen explicitly per module (INV-3), so no host gets it by
  accident, and Topolith logs no warning for it (D-76).

Implements INV-2, INV-3, INV-4, INV-6, INV-8, P-3, D-74, D-75, D-76, D-77, D-81, D-82, D-83, D-84, D-85 and D-120.

- AC-MODE-40 (M5.3): A loopback module's configuration, packages, auto-configurations, entities and migrations are
  imported as for an internal module.
- AC-MODE-51 (M5.3): A loopback module's unmet `requires` fails with TL0004.
- AC-MODE-52 (M5.3): An internal module requiring a loopback module starts.
- AC-MODE-41 (M5.3): An injection point of a loopback module's contract type outside the module, in the host or in
  another internal module, gets the HTTP service client of group `<id>`; no bean is `@Primary`, and startup raises no
  TL0005.
- AC-MODE-48 (M5.3): Inside a loopback module, an injection of the provider's concrete class gets the provider.
- AC-MODE-49 (M5.3): A `List` of another interface the loopback provider implements still contains the provider.
- AC-MODE-50 (M5.3): A loopback module's own injection of its contract gets the provider, not the client.
- AC-MODE-42 (M5.3): A loopback module's routes are served under `/<id>` with `expose.enabled` unset or `true` and are
  matched by `ExposedRequestMatcher`; an explicit `expose.enabled=false` fails with TL0003.
- AC-MODE-43 (M5.3): The loopback client calls the started server's local port under `server.address` (a wildcard
  becomes `localhost`, an IPv6 address is bracketed), the context path and the dispatcher servlet path
  (`spring.mvc.servlet.path`); a call before the server listens raises
  `ModuleUnavailableException` with kind `connection failure`.
- AC-MODE-44 (M5.3): A loopback module in a non-web application, in a `MOCK` web environment, with `server.port=-1` or
  with SSL on (by `server.ssl.enabled` or by `server.ssl.bundle` alone) fails with TL0026, and in a reactive application
  with TL0028.
- AC-MODE-45 (M5.3): A loopback module with a non-blank `remote.url` or `remote.service-id`, or an explicit Boot
  `base-url` (set by the user, in any property source), fails with TL0003 naming the key and the fix, while blank
  `remote.*` values start; a host HTTP service
  group named like the loopback module that holds another interface fails with TL0003.
- AC-MODE-46 (M5.3): A host with no loopback module registers no loopback client group, autowire-candidate resolver,
  base-URL resolver or fault interceptor; a `none` module leaves no trace of them.
- AC-MODE-47 (M5.3): A refresh changing a mode to or from `loopback` logs TL0010 and marks restart pending; a refresh
  changing a loopback module's timeouts or `loopback.*` keys leaves its client unchanged, logs TL0010 naming the module
  and the key, and marks restart pending until the key again matches its running value; a refresh that sets a
  non-blank `remote.url` or `remote.service-id` on a loopback module does the same, and its message notes that the
  next start fails with TL0003.
- AC-MODE-53 (M5): A loopback call in a host with Spring Cloud LoadBalancer on the classpath reaches the host's own
  server: the loopback group carries a Topolith-owned placeholder base URL (`http://localhost/<id>`, replaced by the
  loopback client's own URI factory), so the load balancer's group configurer leaves it alone, and the placeholder
  is not a user setting for AC-MODE-45.
- AC-MODE-54 (M5): With a loopback module, `getBean(<contract>)` outside the module throws
  `NoUniqueBeanDefinitionException`, `getBeanProvider(<contract>).getIfUnique()` returns `null` and `getBeansOfType`
  returns the provider and the client; a loopback provider bean that is `@Primary` or whose class carries
  `@jakarta.annotation.Priority` (also inherited; a provider declared through an interface-typed `@Bean` return is checked by its
  declared type only) fails startup with TL0003 naming the bean and the fix, and the same provider in an
  `internal` module starts.
