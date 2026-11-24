# 60 — Test layers and CI

This file specifies the 0.1 test layers, pull-request gates and acceptance-criterion audit.
It routes work on CI and the evidence required for spec coverage.

## Rules

### R-CI-01 — Test layers

Test descriptor and graph behavior in core, activation and validation in Spring contexts, architecture with failing
fixtures, and end-to-end topologies with real services. Startup failures assert their exact catalog code.
Implements INV-2, INV-4, INV-7 and P-4.

- AC-CI-01 (M1.5): Startup validation tests use `ApplicationContextRunner` and assert the exact diagnostic codes.
- AC-CI-02 (M3.1): Every architecture rule has a deliberately failing fixture.

Graph and end-to-end criteria are AC-DESC-07, AC-DESC-08 and AC-TEST-07.

### R-CI-02 — Pull-request gate

The pull-request gate runs on JDK 21 and 25 and includes the build and acceptance-criterion audit. Implements P-4.

- AC-CI-03 (M0.4): A pull request runs the project gate on both JDK 21 and JDK 25.
- AC-CI-04 (M0.4): The pull-request gate fails when the build or the acceptance-criterion audit fails.

### R-CI-03 — Acceptance-criterion audit

Every criterion has a test named after its id or names its owning milestone slice while that test is still pending.
Criterion definitions have unique ids. Implements P-4 and P-6.

- AC-CI-05 (M0.4): The audit accepts a criterion with a matching named test or an owning milestone slice.
- AC-CI-06 (M0.4): The audit fails a criterion with neither a matching test nor an owning milestone slice.
- AC-CI-07 (M0.4): The audit fails duplicate criterion definitions.
