# Working in this app

You are making ONE change to this application in response to the change request you were given.
Implement it as a **full-stack change**: whatever the request needs across the database schema, the
server (Spring MVC REST on a Spring Boot host), and the Angular front-end — as a small, additive,
local change.

## Rules

- **`data-testid` is immutable public API.** Expose a stable `data-testid` on every element and
  value the feature surfaces. **Never rename or remove a `data-testid` that already exists** — it
  is how the app is tested. Match exactly the `data-testid` values your task's test expects.
- **Schema changes are Flyway migrations.** Add a new versioned migration under
  `src/main/resources/db/migration/`; never edit an applied migration.
- **Data is seeded through the app's own API in tests**, not committed as fixtures. If your feature
  needs new seed capability, extend the `/__test__` seed support. Seed with a `JdbcTemplate` using
  the **explicit ids from the fixture** (JPA `save()` with an IDENTITY id ignores a supplied id and
  generates its own — the spec asserts rows by the fixture's ids, so they must match). `reset`
  should `TRUNCATE ... RESTART IDENTITY` the tables it clears.
- **Audit / side-effect records go through the `Audit` service** (inject `Audit`, call
  `record(...)`). It appends one record per line to the known audit file that tests read — that is
  how audited behaviour is verified (the UI can't show it). Use the exact record text the task's
  test expects; don't invent separate logging for audited behaviour.
- **Keep it additive and local** (this is why the app stays maintainable):
  - a new page is a **new standalone component file** under `src/app/features/<name>/`, plus ONE
    lazy entry in `src/app/app.routes.ts` — give that entry `data: { section, label }` and it
    appears in the nav bar automatically, because the shell reads the router's own config. Never
    edit the shell, and never add a page by growing an existing component;
  - features own their own state — component fields/signals, or a feature-scoped `@Injectable`
    service; there is no global domain store to reach into;
  - shared UI primitives (`src/app/ui/`) are *composed*, not branched with per-feature `if`s;
  - features do not import each other; keep each feature's code together.
  - data access is Angular's `HttpClient` (already provided), injected into a feature service or
    the component that needs it.
- **Do not edit** the build/run scripts (`bin/build`, `bin/start`, `bin/stop`, `bin/e2e`) or this
  file. Use `bin/e2e` to run your test as you work.

## Layout

- `src/main/frontend/**` — the Angular front-end (TypeScript), built by `ng build` into
  `src/main/resources/static`.
  - `src/app/features/<name>/**` — a feature's components and its service. One component per file.
  - `src/app/app.routes.ts` — the route table. A shared surface: a page costs ONE lazy entry here
    and nothing else, and that entry's `data` supplies its nav link.
  - `src/app/app.ts`, `src/app/app.config.ts` — the shell and the app's wiring. Do not edit.
  - `src/app/ui/**` — shared presentational components, *composed*, never branched with
    per-feature `if`s.
- `src/main/java/**` — the Spring backend: `@RestController` classes exposing the REST endpoints
  the front end calls (put them under `/api/` — `SpaConfig` only lets `/api/*` bypass the SPA
  deep-link fallback, so a non-`/api/` path returns the SPA HTML instead of your endpoint), plus
  `@Service`/`@Repository` beans for business logic and data access. `Application`, `SpaConfig`
  and `TestSupportController` are base infrastructure.
- `src/main/resources/db/migration/**` — Flyway migrations (new `V<n>__*.sql` per schema change).
- `bin/e2e` — build, start the app, run your test, stop. Run it to check your work.
