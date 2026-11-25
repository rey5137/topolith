# 90 — Diagnostics, exceptions and glossary

This file defines 0.1 diagnostic meanings, the exception catalog and shared vocabulary.
Rules that raise a diagnostic refer to its stable code; meanings are never reused.

## Rules

### R-CAT-01 — Diagnostic catalog

Each diagnostic has the meaning and severity below; failures include an actionable fix. TL0012 is suppressed for a
violation in the frozen baseline. Implements INV-4, INV-9, INV-10, D-11, D-12, D-23, D-24 and D-25.

| Code | Meaning | Severity | Owning slice |
|---|---|---|---|
| TL0001 | A host has not declared a valid mode for a known module | failure | M1.3 |
| TL0002 | An internal module has no implementation JAR | failure | M1.3 |
| TL0003 | An external module has neither or both URL and service id | failure | M1.5 |
| TL0004 | An internal module's required module is unavailable | failure | M1.3 |
| TL0005 | More than one provider supplies the same contract | failure | M1.5 |
| TL0006 | Two module descriptors, or two API descriptors, declare the same id | failure | M1.1 |
| TL0007 | A non-internal module owns a registered bean, auto-configuration, entity or repository | failure | M3.3 |
| TL0008 | Required module dependencies contain a cycle | failure | M1.1 |
| TL0009 | Service-id discovery needs a missing Cloud integration | failure | M2.2 |
| TL0010 | A refreshed mode or URL/service-id wiring change needs a restart | warning | M2.6 |
| TL0011 | Refreshed endpoint settings are invalid or the client rebuild failed | warning | M2.6 |
| TL0012 | An internal class references another module's non-API class | failure | M3.3 |
| TL0013 | A module or API descriptor is malformed | failure | M1.5 |
| TL0014 | Cloud context classes are present without Topolith refresh integration | warning | M2.6 |
| TL0015 | The remote API-version endpoint is unavailable or inaccessible | warning | M2.4 |
| TL0016 | A mode is declared for a module that is not on the classpath | failure | M1.3 |
| TL0017 | Module packages nest, or another module's API package lies under a module's package | failure | M1.3 |
| TL0018 | The host's auto-configuration or entity-scan packages overlap a module's package, except the standalone module's own | failure | M1.4 |
| TL0019 | An internal module's own DataSource, entity-manager, transaction-manager, Flyway or Liquibase bean is a default candidate and would replace the host's | failure | M1.4 |
| TL0020 | A module's migrations would not run exactly once: no runner or DataSource for its tool, both tools or several changelogs, a host migration location or changelog reaches them, or a tracking table name is too long | failure | M1.4 |
| TL0021 | An internal module's entity is managed by no entity-manager factory, or by several | failure | M1.4 |

The owning slice names the slice that owns the code's acceptance criterion, not the first slice that raises it. M1.3
raises TL0007 early, only when a host's component scan covers a non-internal module's package (AC-MODE-24); M1.4 also
raises it for a non-internal module's entity or repository that a host registers (AC-MODE-11); M3.3 generalises the
check. A host migration location or changelog that reaches a non-internal module's scripts is TL0020 in every mode (AC-MODE-27). A known module is one with a module or API descriptor. TL0002 also covers an `internal` mode for
an id with no module descriptor.

Diagnostic criteria are AC-MODE-02, AC-MODE-05, AC-MODE-08, AC-MODE-21 through AC-MODE-24, AC-MODE-11, AC-MODE-25 through AC-MODE-28, AC-DESC-08,
AC-DESC-10, AC-VAL-01 through AC-VAL-04, AC-ARCH-09, AC-ARCH-11, AC-EXT-06, AC-MODE-13, AC-MODE-18, AC-EXT-22 through
AC-EXT-24 and AC-VAL-10 through AC-VAL-12.

### R-CAT-02 — Exception catalog

`ModuleUnavailableException` represents external transport failure. API-declared exceptions preserve their type
across HTTP, per R-EXT-04. `TopolithException` carries `TLnnnn`-coded topology and configuration failures (D-28);
it is outside INV-6's contract exceptions, and `ModuleUnavailableException` does not extend it. Implements INV-6.

Exception criteria are AC-EXT-09 and AC-EXT-10.

### R-CAT-03 — Vocabulary

Use these terms in descriptors, configuration and diagnostics. Implements P-1 and P-3.

| Term | Meaning |
|---|---|
| Module | A business unit identified by metadata and an id. |
| Contract | An API type a module provides for calls by another module. |
| Host | A Spring Boot application that chooses module modes. |
| Internal | Mode that runs a module's implementation in the host. |
| External | Mode that calls a module through a remote client. |
| None | Mode that does not activate a module or its client. |
| Active for `requires` | An internal module; external dependencies are satisfied by the remote service. |
| Required dependency | A module that an active module needs through `requires`. |
| Optional dependency | A declared dependency whose absence does not make the graph invalid. |
| Restart pending | Configuration requests wiring or a mode different from the running topology. |

- AC-CAT-01 (M4.3): The configuration reference uses these mode and restart-pending meanings.
