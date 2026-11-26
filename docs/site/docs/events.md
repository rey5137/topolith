# Events

Mode-aware events are **not part of 0.1**. This page describes the intended design so you can plan for it.

!!! warning "Roadmap"
    Only the "What works today" section describes 0.1 behaviour. See the [roadmap](roadmap.md).

## What works today

Topolith does not change how Spring application events behave. Between two internal modules in the same host, a
normal Spring application event is delivered in-process as usual. Topolith does not inspect event types, and it does
not check that an event would be lost when a listener's module becomes `external` or `none`. If you publish events
across modules, check this yourself before you change a mode.

## Planned design

- Modules publish events declared in their `*-api`.
- When publisher and listener are both internal, delivery is the normal Spring application event.
- When either one is external, delivery goes through an externalization SPI, with adapters for Spring Modulith event
  externalization (using the transactional outbox), Spring Cloud Stream, or your own.
- When an event would cross a process boundary and no adapter is available, startup fails and names the publisher, the
  listener and the missing adapter.
- Topolith ships no broker.
