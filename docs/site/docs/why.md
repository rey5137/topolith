# Why Topolith

Teams choose between a modular monolith and microservices once, then pay for the choice. A monolith cannot split a
module out when that module needs to scale. Microservices pay for network calls and duplicated infrastructure even
when the team does not need the isolation. Hand-rolled "local bean or remote client" switches are common, but each one
lacks validation, boundary checks and discovery.

Topolith takes the idea of choosing the topology in configuration without asking you to rewrite your application: a
module is declared by metadata, and Topolith uses only standard Spring extension points. There is no custom container,
protocol or broker.

## Compared with the alternatives

| | Plain Spring Modulith | Plain microservices | Topolith |
|---|---|---|---|
| Module boundaries | Checked in-process | Enforced by the network | Checked by descriptor-driven rules and at startup |
| A module in another process | Not modelled | The default | `external` mode, per host, in configuration |
| Changing topology | Not applicable | A rewrite of call sites | Change one `mode` and restart |
| Legacy one-service-per-repo code | Out of scope | Native shape | Adopt with a short checklist; consume as `external` until ready |
| Misconfiguration | Depends on your wiring | Found at runtime | Fails startup with a `TLnnnn` code |
| Business code | Modulith conventions | Client code per service | No framework base classes |

Spring Modulith is complementary, not a competitor. It covers in-process boundaries but has no notion of a remote
module. Topolith enforces its own module boundaries and does not depend on Modulith.

## Decisions it makes for you

- **Hosts must say every mode.** A transitive dependency on a module JAR never activates it silently. Only a
  standalone `*-service` defaults its own module to `internal`.
- **Activation is inclusion-based.** Excluding module classes from scanning would still let auto-configurations,
  imports, entity scanning and migrations leak through. Topolith instead imports each internal module explicitly and
  filters out the auto-configurations of every other one.
- **The URL prefix is fixed per module.** A module is always served under `/<id>`, so client URLs are the same in
  every topology.
- **Unmodified legacy services are not bundled into one JVM.** A child context cannot isolate libraries, resources
  or security filter chains. Legacy services join through a short [adoption checklist](guide/legacy-adoption.md), and
  until then run as `external`.
- **Failure by default.** Boundary checks fail by default; a legacy repository starts from a frozen baseline that can
  only shrink.
