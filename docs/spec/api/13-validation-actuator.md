# 13 — Validation and diagnostics

This file specifies startup topology validation, readiness version warnings and the topology actuator endpoint.
Diagnostic meanings are defined in `reference/90-catalog.md`.

## Rules

### R-VAL-01 — Fail invalid topology

Reject missing modes (TL0001), missing internal implementations (TL0002), invalid external endpoint selection
(TL0003), unmet required modules (TL0004), duplicate providers (TL0005), duplicate ids (TL0006), required-edge
cycles (TL0008), a mode for a module not on the classpath (TL0016), nested module packages (TL0017) and an internal
module whose host API descriptor and module descriptor declare different `provides` sets (TL0022). Validate descriptors
at startup, including hand-written ones; malformed descriptors fail with TL0013. Each failure includes its code and a
fix-it message. Implements INV-3, INV-4, D-19, D-31 and D-33.

- AC-VAL-01 (M1.5): Missing or conflicting external endpoint settings fail with TL0003 and a fix-it message.
- AC-VAL-02 (M1.5): Two providers of one contract fail with TL0005 and identify the contract and providers.
- AC-VAL-03 (M1.5): A malformed hand-written descriptor fails startup with TL0013 and a fix-it message.
- AC-VAL-04 (M1.5): Each topology failure includes an actionable fix-it message.
- AC-VAL-09 (M1.5): A valid hand-written descriptor is accepted and used for topology validation.
- AC-VAL-14 (M1.5): An internal module whose host API descriptor declares a different `provides` set fails with TL0022,
  naming the contracts unique to each side; the same host passes with the module `none` or `external` (D-33).

Mode and graph failure criteria are AC-MODE-02, AC-MODE-05, AC-MODE-08 and AC-DESC-08.

### R-VAL-02 — Ownership validation

Fail startup with TL0007 when a bean or auto-configuration is owned by a non-internal module. Determine ownership
from the declaring class or resource, not merely bean type. Implements INV-2, INV-4 and D-7. Ownership criteria are
AC-ARCH-09 and AC-ARCH-10.

### R-VAL-03 — Discovery integration

An external service id without the needed LoadBalancer integration fails startup with TL0009 and an actionable
starter hint. Implements INV-4 and P-3. Its criterion is AC-EXT-06.

### R-VAL-04 — API version readiness

At readiness compare the host `*-api` JAR's API descriptor `api-version` major against the internal module's
descriptor major, or for an external module against its remote provider's version read from
`/<id>/.topolith/api-version` (plain text; D-44 to D-47). The remote read is asynchronous (D-24). A mismatch logs warning TL0023 once and neither fails startup nor changes readiness
(D-34). Use the mandatory client timeouts in R-EXT-03 for the
remote check. If the endpoint is unreachable, returns any non-2xx response, or returns a 2xx response without a readable
version, log warning TL0015; the failed version check does not make an otherwise-ready host unready. Implements INV-4,
D-22, D-24 and D-34.

- AC-VAL-05 (M1.5): Internal readiness warns by default on a host API versus internal descriptor major mismatch.
- AC-VAL-06 (M2.4): The provider exposes its API version at `/<id>/.topolith/api-version`.
- AC-VAL-07 (M2.4): External readiness warns by default on a host API versus remote endpoint major mismatch.
- AC-VAL-10 (M2.4): An unreachable version endpoint logs warning TL0015 without making an otherwise-ready host unready.
- AC-VAL-11 (M2.4): Version endpoint responses 401 and 403 each log warning TL0015 without making an otherwise-ready
  host unready.
- AC-VAL-12 (M2.4): A version endpoint response 404 logs warning TL0015 without making an otherwise-ready host unready.
- AC-VAL-13 (M2.4): A 5xx response or a 2xx response with an unreadable body each logs warning TL0015 without making
  an otherwise-ready host unready.

### R-VAL-05 — Running topology status

The Topolith actuator endpoint reports running module modes, restart-pending status and refresh failures as required
by D-11 and D-14; D-35 fixes the endpoint shape. Implements INV-4 and INV-9. Refresh criteria are AC-MODE-13, AC-EXT-22, AC-EXT-23 and AC-EXT-24.

- AC-VAL-08 (M1.5): The actuator endpoint reports the running mode of each known module.
