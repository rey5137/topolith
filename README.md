# Topolith

*Write modules once. Choose your topology in config.*

Topolith lets a Spring Boot business module run **in-process**, **remote** or **not at all**, chosen per host in
configuration, without changing the module's code. The same `user` and `payment` modules can ship as one modular
monolith, as separate services, or anything in between, and move between those shapes over time.

```yaml
topolith:
  modules:
    payment: { mode: internal }
    user:    { mode: external, remote: { service-id: user-service } }   # or remote.url: http://user:8080
    audit:   { mode: none }
```

- **One repo or many.** Modules live in one multi-module repo, or in their own legacy service repos that adopt
  Topolith and get bundled into one image later.
- **Fixed endpoint or service discovery** for remote modules, on Spring's HTTP service clients and Spring Cloud
  LoadBalancer.
- **Boundaries enforced** by Topolith itself: build-time rules, ArchUnit tests and startup checks, all generated from
  the module's metadata. Spring Modulith is an optional add-on.
- **Non-opinionated:** no base classes, no custom protocol, no broker. Business code needs no Topolith type.

> **Status:** early development. The design is [RFC 0001](rfc/0001-architecture.md); the spec is in
> [`docs/spec/`](docs/spec/SPEC.md) and the plan in [`docs/plan/mvp-plan.md`](docs/plan/mvp-plan.md). Nothing is
> released yet.

## Requirements

Java 21+, Spring Boot 4.2+. Spring Cloud is needed only for service discovery.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE)
