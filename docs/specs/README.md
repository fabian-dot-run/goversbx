# Specs

goversbx is developed spec-first from here on: a feature starts as a spec under this directory,
the spec is reviewed and accepted, and only then is it implemented against the spec's scenarios.
`docs/status.md` stays the resume point and `docs/plan.md` the dated design history; a spec is
the contract for one feature.

## Layout

```
docs/specs/NNNN-<slug>/epic.md      problem, options considered, decision, slices, deferred items
docs/specs/NNNN-<slug>/slice-N.md   one deliverable slice: scope, contract, scenarios, tests, open questions
```

`NNNN` is a running number. An epic is cut into slices that are each shippable on their own
(releasable, tested, documented). A slice spec is the unit of implementation.

## Lifecycle

Each spec carries a `Status:` line: `draft` -> `accepted` -> `implementing` -> `done`
(or `dropped`, with the reason). A spec is accepted by the user, not by the agent. During
implementation the spec is updated when reality diverges, in the spec itself, with a dated note;
the scenarios are the acceptance criteria and must match the tests that exist when the slice
is `done`.

## Scenarios

Scenarios are written as Given/When/Then and each names the test level that pins it:

- `unit` - domain/application, `daemon/src/test`
- `contract` - black-box daemon process + `FakeSbx`, `daemon/src/binaryTest` (also the source of
  native-image metadata, so every new route, JSON type and sbx interaction needs one here)
- `e2e` - real sbx, `daemon/src/e2eTest`

A scenario without a test level is a bug in the spec.
