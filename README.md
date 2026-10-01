# officehq-angular-spring — base repository (Angular SPA + plain Spring Boot)

A **base repository** for the `ui-long-degradation-test` harness — **one technology stack**:
front-end **Angular** SPA (v21, standalone components, lazy routes), backend **plain Spring Boot**
(`@RestController` + `@Service`) on in-memory H2. No OfficeFloor anywhere.

This is the **ecological baseline**: the stack a typical enterprise team actually ships, and the
thing the study is implicitly argued against. Its backend pairs with the `spring` arm of
`~/spring-petclinic-rest-long-degradation-test`, so the two studies line up on the mutative side.

**It is not a control in the experimental sense.** Against the additive arms it varies *two*
dimensions — framework and backend — so on its own it attributes nothing; read it as external
validity. The one-variable controls are `~/officehq-angular-officefloor` (framework held against
React, backend constant) and the React pair (architecture, framework constant).

The front end is byte-identical to `~/officehq-angular-officefloor` — this repo was cloned from it
and only the backend was swapped — so a front-end difference between the two arms can only come
from the backend they talk to.

- Base repos are **home-level sibling directories**, one per stack, named
  `~/officehq-<frontend>-<backend>` so both layers are visible (`~/officehq-react-officefloor`,
  `~/officehq-<frontend>-<backend>`, …) — the **front-end and the backend may both vary** between
  stacks. The study compares stacks by running the harness against each in turn — which stack best
  resists erosion.
- The harness (`~/ui-long-degradation-test`, `config.yaml → app.repo`) reads this folder at branch
  **`base-empty`**, worktrees it onto `evolve/<run_id>/<condition>/chain<n>`, and commits each
  checkpoint there. This branch is only ever read.
- It honours the **App contract** — see `~/ui-long-degradation-test/docs/SUT_CONTRACT.md`.
- **Try another stack:** create a new sibling `~/officehq-<frontend>-<backend>` (different
  front-end, different backend, or both), satisfy the same `BASE_CHECKLIST.md`, and point
  `app.repo` at it. Each is its own run.

**Status: green.** `bin/build` produces the one jar and `bin/e2e` verified against the real jar
that the shell renders and a deep link survives a refresh. Confirmed the packaged jar contains **no
OfficeFloor libraries**. Node is pinned to v22.12.0 for Angular 21's CLI and is cached in `~/.m2`
after the first build, so the Landlock-confined gate needs no network.
