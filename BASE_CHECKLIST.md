# Base-repo checklist

This is a **base repository** for `ui-long-degradation-test` (see that repo's `DESIGN.md` and
`docs/SUT_CONTRACT.md`). The harness points `config.yaml → app.repo` at this folder, worktrees the
`base-empty` branch onto a fresh `evolve/<run_id>/<condition>/chain<n>` branch, and evolves it —
one full-stack English change request per checkpoint — committing each checkpoint on that run
branch. The base branch is only ever read.

This stack is **Angular (front-end) + plain Spring Boot (backend)** on in-memory H2 — hence the
name `officehq-angular-spring`. It is the ECOLOGICAL BASELINE, not a one-variable control: domain
endpoints are `@RestController` methods, there is no OfficeFloor, and §B's OfficeFloor items below
do not apply to it. Node is pinned to v22.12.0 for Angular 21's CLI.

**This folder is green** (§A–§H verified). Because the harness only depends on the *contract* (not the tech), you create a
new stack as a **home-level sibling** `~/officehq-<frontend>-<backend>` (name both layers, since
either may vary), satisfy the same checklist with a different technology, and point `app.repo` at
it — that is how different technology stacks are compared, one run each, to see which resists
erosion best. §B below is written for this stack — **Spring Boot 4 hosts, OfficeFloor REST via the
officefloor-rest-spring-boot-4-starter, H2** — but a different-backend sibling adapts §B's specifics
while keeping the same *properties*: one embedded JVM (no daemon/container), schema migrated on
boot, a static-served SPA, an `/actuator/health` readiness probe, and the `/__test__` seed endpoint.

---

## A. The base must start NEAR-EMPTY

- [ ] **No domain tables.** `src/main/resources/db/migration/` has no Flyway migrations at base
      (empty dir with `.gitkeep`). cp01 adds `V1__*.sql` creating the first real tables.
- [ ] **No domain features.** The front-end is a bare shell (empty home); the backend has no domain
      `officefloor/rest` routes yet (only Spring's `/actuator/health` + the `/__test__` support).
      The app **builds, boots, and serves the shell** as-is.
- [ ] **It is green before cp01.** `bin/build` succeeds and `bin/start` serves `/actuator/health`
      = UP and the shell renders, from a clean checkout of `base-empty`.

## B. One embedded stack (DESIGN.md §14, §15 — must run under Landlock)

- [ ] **Single JVM, no daemon/container.** Spring Boot 4 (`spring-boot-starter-parent` 4.1.0) is the
      host; OfficeFloor REST is added via `net.officefloor.springboot:officefloor-rest-spring-boot-4-
      starter`. Standard `@SpringBootApplication` main; `spring-boot-maven-plugin` repackage.
- [x] **Domain REST is Spring MVC** — `@RestController` classes under `/api/`, the mutative
      baseline. There is no per-endpoint wiring unit, so the harness's node-closure and config_loc
      columns are blank for this arm (see `stack.yaml`).
- [ ] **In-memory H2** (`spring.datasource.url=jdbc:h2:mem:officehq;DB_CLOSE_DELAY=-1`); dies with
      the JVM.
- [ ] **Flyway on boot** (`spring.flyway.enabled=true`, `ddl-auto=none`), from
      `src/main/resources/db/migration` — builds the schema up from empty.
- [ ] **SPA served from `src/main/resources/static`** (Spring serves `static/`); `src/main/frontend`
      builds into `static/`. SPA deep-link fallback is `SpaConfig.java` (unknown non-`api/` →
      `index.html`).
- [ ] **`/actuator/health`** (Spring Actuator) — the harness readiness probe
      (`config.yaml → app.health_url`).
- [ ] No external services, no network egress needed to build/boot (toolchain resolvable offline
      or pre-warmed — the agent turn is Landlock-confined; note `frontend-maven-plugin` + Maven
      must have node/deps available offline or pre-fetched).

## C. Fixed operational scaffolding (PINNED — agent runs but never edits; DESIGN.md §15)

These commands must stay constant across checkpoints even as the app evolves. They are in
`config.yaml → isolation.pin_files` and restored to authored before every gate.

- [ ] `bin/build` — Maven build; `frontend-maven-plugin` builds the SPA into `static/`, then
      `spring-boot-maven-plugin` repackages the app into `target/*.jar`.
- [ ] `bin/start` — `java -jar target/*.jar --server.port=$PORT --spring.profiles.active=harness`
      (main `net.officefloor.hq.app.Application`); exits 0 once launching (records pid for
      `bin/stop`). The `harness` profile enables `/__test__`.
- [ ] `bin/stop` — kill the JVM / free `$PORT`; **idempotent** (safe when nothing runs / after a
      crash). The harness may also kill by port.
- [ ] `bin/e2e` — build + start + run **only the specs currently present** in `e2e/specs` +
      stop. This is what the agent runs to test as it works (it only ever sees its own spec).
- [ ] All four are executable (`chmod +x`) and depend only on `$PORT` (+ their own internals).

## D. The test contract (DESIGN.md §3, §9)

- [ ] **`data-testid` everywhere behaviour is observed.** The shell and every feature expose
      stable `data-testid` anchors; nothing the tests rely on uses CSS/DOM/text.
- [ ] **`data-testid` is immutable public API** — once introduced, never renamed/removed. State
      this rule in `CLAUDE.md`/`AGENTS.md` (pinned) so every agent turn obeys it.
- [ ] **`/__test__` seed endpoint** (profile-guarded: only active under the harness's launch
      profile). `POST /__test__/reset` (truncate all domain tables **and clear the audit file**) +
      `POST /__test__/seed` (insert a fixture payload). This is **app code and evolves** with the
      schema (NOT pinned); a change that breaks a prior spec's seed is a *seed-path* regression (§6).
- [ ] **Audit file — the second assertion channel.** Audited/side-effect behaviour is written one
      record per line to a known file via the `Audit` service (`app.audit.file`, `AUDIT_FILE`; both
      `bin/start` and `bin/e2e` set it). Specs read it through `e2e/support/audit.ts`. The path +
      one-line-per-record format is a stable contract like `data-testid`; a broken audit record for
      a prior rule is a behaviour-loss regression (DESIGN.md §3, §6).
- [ ] **Playwright project** in `e2e/` (`playwright.config.ts`, `package.json`) with `baseURL` =
      `http://localhost:$PORT`, `data-testid` as the default test id, strict awaiting. The harness
      copies authored `cpNN` specs into `e2e/specs/`; do not commit specs to the base.
- [ ] A shared `e2e/support/` with a `beforeEach` reset+seed helper (`seed.ts`) and the audit
      reader (`audit.ts`).

## E. Agent-facing instructions (PINNED)

- [ ] `CLAUDE.md` (and `AGENTS.md`) tell the agent: it is making a **full-stack** change
      (migration + OfficeFloor server + front-end) from a plain-English request; the `data-testid`
      immutability rule; that it can run `bin/e2e` to test; the additive/opinionated conventions of
      the shell (routing, no global store, closed primitives, slice boundaries, scoped styles).
- [ ] These never leak the checkpoint sequence (no cpNN references, no prior-request hints).

## F. Layout the harness expects (matches `config.yaml`)

```
bin/{build,start,stop,e2e}                       # pinned scaffolding (C)
pom.xml                                           # Spring Boot 4 app -> one runnable jar
src/main/java/**/*.java                           # backend: Application, SpaConfig, TestSupportController, REST logic (source_globs.backend)
src/main/resources/application.properties         # H2 + Flyway + Actuator
src/main/resources/officefloor/rest/**/*.yml      # additive OfficeFloor REST routes (shared_surfaces.backend)
src/main/resources/db/migration/                  # Flyway migrations (empty at base)
src/main/resources/static/                         # SPA build output, served by Spring
src/main/frontend/**/*.{ts,tsx}                   # front-end source (source_globs.frontend); builds into static/
src/main/frontend/{router,ui}/                     # shared_surfaces.frontend
e2e/{playwright.config.ts,package.json,support/}  # Playwright project (specs copied in per cp)
CLAUDE.md, AGENTS.md                               # pinned agent instructions
```

- [ ] `config.yaml → app.source_globs` / `shared_surfaces` match the real paths (so per-layer
      erosion and boundary-violation counts are correct — DESIGN.md §8).

## G. Git

- [ ] The base state lives on branch **`base-empty`** (= `config.yaml → app.base_ref`).
- [ ] `.gitignore` excludes build output (`target/`, `node_modules/`, `*.jar`, and the generated
      `src/main/resources/static/assets/`) so the agent's committed delta is source only.
- [ ] Local repo is enough (the harness reads it by path); a remote is optional.

## H. Smoke test before wiring into a run

- [ ] From a clean `base-empty` checkout: `bin/build` → `bin/start` → `curl /actuator/health` = UP
      → shell renders → `bin/stop` frees the port.
- [ ] Drop one throwaway spec into `e2e/specs/` and confirm `bin/e2e` builds, serves, runs it, and
      stops — the whole agent-test loop end to end.
- [ ] Confirm the whole thing runs under the harness's Landlock allowlist
      (`python harness/landlock_selftest.py` in the harness repo; add toolchain binds in
      `config.yaml → isolation` as needed).
