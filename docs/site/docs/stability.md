# API stability

Topolith is pre-1.0. Every public type in every published module is marked `@Incubating`, and its shape may change
before `1.0.0`.

- Expect source and binary changes between minor releases. The changelog lists them under notes for adopters.
- The `TLnnnn` diagnostic codes are stable: a code's meaning is never reused.
- Read the changelog when you upgrade.
- Pin the version through `topolith-bom` so all Topolith artifacts move together.

The published artifacts are `topolith-parent`, `topolith-bom`, `topolith-core`, `topolith-spring-boot`,
`topolith-spring-cloud`, `topolith-processor` and `topolith-spring-test`, under the group `io.github.rey5137`. The
samples are not published.
