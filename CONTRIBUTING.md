# Contributing

Thanks for helping. Topolith is in **early development**: the design is [RFC 0001](rfc/0001-architecture.md), the spec
in `docs/spec/` is being written for 0.1, and code starts at milestone M0 (`docs/plan/mvp-plan.md`). Design feedback,
questions and typo fixes are welcome now.

Questions and API ideas go to GitHub Discussions; bugs and tasks to Issues.

---

## Finding your way around the spec

`docs/spec/` is the source of truth. You don't need to read all of it.

- Start at **[`docs/spec/SPEC.md`](docs/spec/SPEC.md)**: its §1 routing table tells you which child file covers your
  task, and §2 lists the invariants `INV-1..10`. The first five lines of each child file say what it covers.
- Every rule has a stable id: `R-*` rules, `AC-*` acceptance criteria, `INV-*` invariants, `TLnnnn` codes, `D-*`
  decisions, `Q-*` open questions. Search for one with `grep -rn 'R-ACT-03' docs/spec`.
- The *why* behind a rule is in `reference/92` (`D-n`) and RFC 0001.
- **Cite ids, don't restate the spec** in code comments, commit messages or new docs (`R-EXT-04`, `AC-ACT-01`,
  `INV-2`, `D-7`).
- If code and spec disagree, or the spec is silent, open an issue rather than picking one.
- The spec and the plan hold what to build, never progress: no checkboxes, status columns or work logs. Milestone
  status is read from git.

## Non-negotiables

- **Invariants `INV-1..10`** (SPEC.md §2). Breaking one is an architecture decision, never a patch: raise it in an
  issue or RFC first. The ones code most often touches: business code needs no Topolith type (INV-1); a module that
  isn't internal contributes nothing (INV-2); loud over silent (INV-4); mode parity (INV-6); one-way module
  dependencies (INV-7).
- **Never make a topology "work" by dropping a check.** A startup failure that names the problem beats a host that
  starts with a silently missing module, call or event.
- **Module boundaries** (INV-7), enforced by ArchUnit: `topolith-core` imports only the JDK; only `topolith-spring-*`
  modules import Spring; Spring Cloud and Spring Modulith only in optional modules; no Lombok anywhere.
- **Diagnostic codes** (`reference/90`): a code is added there before the code that raises it, and its meaning is
  never reused (INV-10).

## Writing code

- **Match surrounding code**: naming, comment density, idiom. No speculative abstractions, no stubs for later
  milestones.
- **Comments explain *why*,** at the line they guard, once.
- **Tests** carry the criterion id in their name (`ac_act_03_...`). Every startup failure is tested with
  `ApplicationContextRunner` and asserts the exact `TLnnnn` code. No mocks of Spring internals.

## Building

Requirements: JDK 21+, Docker (for end-to-end tests).

```bash
./mvnw verify                             # all modules
./mvnw verify -pl topolith-spring-boot -am  # one module and what it depends on
```

## Pull requests

- Branch from `main` and keep PRs focused. `main` is protected.
- Milestone work happens on an `mN-<name>` branch (`m1-activation`). Once the milestone's exit criteria are met it is
  merged into `main` and the last commit it brings onto `main` is tagged `mN-verified`.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) with the module as scope and the
  ids covered: `feat(spring-boot): inclusion-based activation (AC-ACT-01..05)`. Spec-only edits use `docs(spec)`.
  Each commit is green on its own.
- PRs are rebase-merged after one approving review with CI green.
- New public API that may still change is marked `@Incubating`.

## Changing the spec or the API

- **Spec edits**: change the owning file only, keep ids stable (never renumber; retire an id by marking it `Retired`),
  and list the ids you touched in the PR.
- **New design choices** are recorded as a `D-n` in `reference/92`, or a `Q-n` while still open.
- **A public signature change, a rule's meaning, or an `INV-*`** needs an accepted RFC first (`rfc/README.md`).

## Using AI assistants

AI-assisted contributions are welcome. You are the author: you must understand every line you submit, be able to
explain it in review, and have run the build yourself. Say in the PR if a substantial part was generated.

Everything that applies to a contributor is in this file and in `docs/spec/`; there is nothing an assistant needs that
a human does not.

## License

By contributing you agree that your contribution is licensed under the [Apache License 2.0](LICENSE). There is no CLA.
