# 10 — Module descriptors

This file specifies module metadata, its generated and hand-written forms, and the module graph.
It routes work on `@TopolithModule`, contract inference and API descriptors.

## Rules

### R-DESC-01 — Module declaration

An optional `@TopolithModule` on `package-info.java` declares the module id, API packages, required and optional
dependencies. Business code needs no Topolith base class or other Topolith type. Implements INV-1, P-1 and D-9.

- AC-DESC-01 (M1.2): Processing an annotated package emits `META-INF/topolith/modules/<id>.properties`.
- AC-DESC-02 (M1.2): The emitted descriptor records the id, API packages, `requires` and `optional` declarations.
- AC-DESC-03 (M1.2): A module's business classes work without importing a Topolith type.

### R-DESC-02 — Provided contracts and version

The processor infers `provides` from interfaces in the API package that the module implements and records the
project version as `api-version`. Implements INV-5, P-6 and D-9.

- AC-DESC-04 (M1.2): An implemented API interface is included in `provides`; an unimplemented interface is not.
- AC-DESC-05 (M1.2): The generated `api-version` equals the Maven project's version.

### R-DESC-03 — API artifact metadata

The `*-api` JAR carries `META-INF/topolith/apis/<id>.properties` with id, API packages, `provides` and `api-version`.
Implements INV-5 and P-6.

- AC-DESC-06 (M1.2): The API JAR contains a descriptor with all four fields for its module.

### R-DESC-04 — Graph and identity

The core model parses descriptors and resolves required and optional edges. Duplicate module ids fail with TL0006;
cycles in `requires` fail with TL0008. An absent optional dependency is not an unmet requirement. Implements INV-4.

- AC-DESC-07 (M1.1): Parsing preserves module identity, API metadata, required and optional dependency edges.
- AC-DESC-08 (M1.1): Duplicate ids fail with TL0006, and a required-edge cycle fails with TL0008.
- AC-DESC-09 (M1.1): An absent optional dependency does not make the graph fail an unmet-requirement check.
