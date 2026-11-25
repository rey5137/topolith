# 90 — Diagnostics, exceptions and glossary

This file defines 0.1 diagnostic meanings, the exception catalog and shared vocabulary.
Rules that raise a diagnostic refer to its stable code; meanings are never reused.

## Rules

### R-CAT-01 — Diagnostic catalog

Each diagnostic has the meaning and severity below; failures include an actionable fix. TL0012 is suppressed for a
violation in the frozen baseline. Implements INV-4, INV-9, INV-10, D-11, D-12, D-23, D-24 and D-25.

| Code | Meaning | Severity | Owning slice |
|---|---|---|---|
| TL0001 | A host has not declared a known module's mode | failure | M1.3 |
| TL0002 | An internal module has no implementation JAR | failure | M1.3 |
| TL0003 | An external module has neither or both URL and service id | failure | M1.5 |
| TL0004 | An internal module's required module is unavailable | failure | M1.3 |
| TL0005 | More than one provider supplies the same contract | failure | M1.5 |
| TL0006 | More than one module descriptor declares the same id | failure | M1.1 |
| TL0007 | A non-internal module owns a registered bean or auto-configuration | failure | M3.3 |
| TL0008 | Required module dependencies contain a cycle | failure | M1.1 |
| TL0009 | Service-id discovery needs a missing Cloud integration | failure | M2.2 |
| TL0010 | A refreshed mode or URL/service-id wiring change needs a restart | warning | M2.6 |
| TL0011 | Refreshed endpoint settings are invalid or the client rebuild failed | warning | M2.6 |
| TL0012 | An internal class references another module's non-API class | failure | M3.3 |
| TL0013 | A module descriptor is malformed at startup | failure | M1.5 |
| TL0014 | Cloud context classes are present without Topolith refresh integration | warning | M2.6 |
| TL0015 | The remote API-version endpoint is unavailable or inaccessible | warning | M2.4 |

Diagnostic criteria are AC-MODE-02, AC-MODE-05, AC-MODE-08, AC-DESC-08, AC-VAL-01 through AC-VAL-04,
AC-ARCH-09, AC-ARCH-11, AC-EXT-06, AC-MODE-13, AC-MODE-18, AC-EXT-22 through AC-EXT-24 and AC-VAL-10 through
AC-VAL-12.

### R-CAT-02 — Exception catalog

`ModuleUnavailableException` represents external transport failure. API-declared exceptions preserve their type
across HTTP, per R-EXT-04. Implements INV-6.

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
