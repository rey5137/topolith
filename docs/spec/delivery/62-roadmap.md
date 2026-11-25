# 62 — 0.1 scope and roadmap

This file defines the 0.1 release scope, milestone exits, RFC coverage and exclusions.
It maps RFC 0001's 0.1 items to the child rules that implement them.

## Rules

### R-ROAD-01 — 0.1 release scope

Release 0.1 consists of core, Spring Boot and optional Cloud integrations, processor, Spring test support, BOM and
reference samples. It supports internal, external and none modes, inclusion-based activation, fixed URL and service-id
endpoints, timeouts, startup validation, ProblemDetail errors, ArchUnit rules, refresh and restart-pending status.
It implements INV-1 through INV-10 and D-1 through D-25 to the extent not deferred below.

Release criteria include AC-BUILD-01, AC-EXT-09, AC-TEST-05 and AC-TEST-06; refresh is covered by AC-MODE-13 and
AC-EXT-18 through AC-EXT-24.

### R-ROAD-02 — Milestone exits

The 0.1 milestones are M0 spec and bootstrap, M1 modules and activation, M2 external mode, M3 boundaries and M4
reference adopter and release. Implements P-3 and P-4.

- AC-ROAD-01 (M1.5): A two-module host covers internal and none combinations without non-internal contributions.
- AC-ROAD-02 (M2.5): A hybrid host passes the same contract tests as the monolith.

Other exit criteria are AC-CI-03, AC-CI-04, AC-ARCH-01, AC-ARCH-11, AC-BUILD-13 and AC-BUILD-14.

## RFC 0001 0.1 coverage

Items below refer to RFC 0001; rows for design sections cover the parts used by 0.1, with later features excluded below.

| RFC section or item | Rule ids |
|---|---|
| Summary: metadata, topology, standard Spring | R-DESC-01, R-MODE-01, R-MODE-02 |
| Summary: repository layouts | R-BUILD-01, R-BUILD-03, R-BUILD-05 |
| Motivation R1–R3: multi-module repo, adoption, bundling | R-BUILD-03, R-BUILD-04, R-BUILD-05 |
| Motivation R4–R6: endpoint choice, own boundaries, minimal API | R-EXT-02, R-ARCH-01, R-DESC-01 |
| §2: metadata, extension points, explicit modes | R-DESC-01, R-MODE-01, R-MODE-02 |
| §2: override, strict boundaries | R-EXT-06, R-ARCH-03 |
| §3.1: descriptor, provides, version, API JAR | R-DESC-01, R-DESC-02, R-DESC-03 |
| §3.2: modes, inclusion and auto-configuration | R-MODE-01, R-MODE-02, R-MODE-03, R-EXT-01 |
| §3.2: persistence | R-MODE-04 |
| §3.3: startup validation | R-VAL-01, R-VAL-02, R-VAL-03 |
| §3.3: API-version major readiness warning | R-VAL-04 |
| §4.1: 0.1 framework modules and samples | R-BUILD-01, R-BUILD-03 |
| §4.2: API-only business module dependencies | R-BUILD-02, R-ARCH-01 |
| §4.3: adoption checklist and external consumption before adoption | R-BUILD-05, R-EXT-01, R-EXT-06 |
| §4.4: normal host, platform, dependency enforcers, latest-module CI | R-BUILD-04 |
| §5: contracts, service groups, endpoint selection and discovery | R-EXT-01, R-EXT-02 |
| §5: timeouts, errors, exposure, client escape hatches | R-EXT-03, R-EXT-04, R-EXT-05, R-EXT-06 |
| §6: D-2 transaction boundary; contract types in the M3.1 plan | R-EXT-07, R-ARCH-02 |
| §8: API-only dependencies and architecture | R-BUILD-02, R-ARCH-01 |
| §8: frozen baseline and startup enforcement | R-ARCH-03, R-ARCH-04, R-ARCH-05 |
| §9: deterministic modes for later AOT | R-MODE-01 |
| §10: 0.1 components and modes | R-ROAD-01, R-BUILD-01, R-MODE-01 |
| §10: activation, endpoints, timeouts and startup validation | R-MODE-02, R-MODE-04, R-EXT-02, R-EXT-03, R-VAL-01 |
| §10: ProblemDetail errors and ArchUnit rules | R-EXT-04, R-ARCH-01, R-ARCH-02 |
| §10: reference adopter and release exit | R-BUILD-03, R-TEST-03, R-ROAD-01 |
| §11: D-1 through D-5 as resolved in reference/92 | R-BUILD-06, R-EXT-07, R-EXT-01, R-ARCH-03, R-MODE-01 |
| §12: inclusion, explicit mode and metadata | R-MODE-01, R-MODE-02, R-DESC-01 |
| §12: platform, ownership and fixed prefix | R-BUILD-04, R-VAL-02, R-EXT-05 |
| Compatibility: incubating public types | R-BUILD-06 |

## 0.1 exclusions

- RFC §3.2 and §10: loopback, latency and fault injection, by-value proxy and full mode matrix are 0.2.
- RFC §6 and §10: remaining parity machinery, including mutation proxy, outgoing authentication, context propagation
  and Observation tags, is 0.2. D-2 and the M3.1 contract-type checks are explicitly scheduled in the 0.1 plan.
- RFC §3.3, §7 and §10: mode-aware events, cross-process event adapters and lost-event validation are 0.3.
- RFC §9 and §10: topology file generation, Helm/Kubernetes generation and extraction reports are 0.4.
- RFC §4.3, §8 and §10: the automated adoption goal, per-module config tooling and data-ownership checks are 0.5;
  0.1 includes the adoption documentation and persistence isolation.
- D-19: the `topolith:verify` Maven goal for hand-written descriptor bytecode verification is deferred beyond 0.1;
  startup still validates descriptors.
- D-16: retry, circuit breakers and declarations of idempotency are deferred beyond 0.1; timeouts remain mandatory.
- RFC §8 and §10: the Modulith bridge, graph docs, Backstage export, API compatibility gate, hybrid local development
  and Consul registrar SPI are later. D-11 requires the topology actuator status in 0.1 despite the RFC's later row.
- RFC §9 and D-5: native images and the AOT topology-drift guard are outside 0.1.
- RFC §10: runtime topology changes, in-JVM isolation of unmodified services and a built-in saga engine are not
  planned.
