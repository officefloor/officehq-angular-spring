# Working in this app

<!-- AGENTS.md version 1. Maintained by the ui-long-degradation-test harness and copied into every
     app by it, identical for every stack. Do not edit the copy in the app: it is replaced at every
     checkpoint, and the copy that was used is recorded (path + sha256) in
     evolve-results/provenance.json. Stack-specific guidance lives in the app's own DESIGN.md. -->

## 1. How you work

- Implement the ONE change request you have been given. A single change may span the stored data
  and its schema, the server, and the front end.
- **Read `DESIGN.md` first.** It describes this implementation — its stack, layout and conventions
  — and how to do things in it. Follow it.
- Your change is checked by an automated Playwright test that drives the running app through its
  UI. The test is in `e2e/specs/`. **Run `bin/e2e`** to build the app, start it, run the test and
  stop it — run it as you work and iterate until it passes.
- **Do not edit** the test, `bin/*`, `e2e/support/*`, `AGENTS.md`, `CLAUDE.md` or `DESIGN.md`.

The rest of this file is how the app is tested. It is the same for every implementation of this
app.

## 2. The UI is tested only through `data-testid`

- Every element a test acts on, and every value it reads back, carries a `data-testid`. Tests use
  these only — never CSS classes, page structure, or visible wording.
- **A `data-testid` is a permanent public API.** Once it exists, never rename or remove it —
  unless the change request removes the feature it belongs to. Match exactly the `data-testid`
  values, and the values shown in them, that your test expects.

## 3. Side effects are tested through the audit file

Behaviour that is not visible on screen (for example "record that this happened") is written to the
audit file, one record per line, through the app's audit service. `bin/start` and `bin/e2e` set its
path in `AUDIT_FILE`; tests read it with `e2e/support/audit.ts`. Write records exactly in the form
your test expects.

## 4. Test data

Every test arranges its own data. Before each test, `e2e/support/seed.ts`:

1. calls `POST /__test__/reset`, which must empty every table and the audit file, then
2. calls `POST /__test__/seed` with a JSON fixture holding exactly the rows that test needs.

There is no shared dataset: two tests never see each other's data. The endpoints are part of the app
and you extend them as the app grows. They exist only under the test profile that `bin/start` uses,
and the app itself must never call them.

### 4.1 The fixture

A fixture is a JSON object. Each top-level key is a collection of rows (`clients`, `invoices`,
...), a settings object, or a single value:

```json
{
  "asOf": "2026-02-05",
  "clients":  [{ "id": 1, "name": "Acme Ltd", "email": "ops@acme.example" }],
  "invoices": [{ "id": 1, "clientId": 1, "status": "SENT", "issueDate": "2026-01-10",
                 "dueDate": "2026-02-09",
                 "lineItems": [{ "id": 1, "description": "Work", "qty": 1, "unitPrice": 200 }] }]
}
```

- Rows carry the ids the test then uses in its `data-testid`s (`invoice-row-1`). Store them as given.
- A field means the same thing everywhere it appears. Store the value you are given; do not
  reinterpret it.
- **A field a fixture leaves out takes its default.** The default must be the ordinary value a newly
  created record would get (no discount, no rate, and so on).
- **`asOf` is the test's "today".** When a fixture gives `asOf`, the app must treat that date as the
  current date for everything date-dependent (overdue, ageing, windows, "this week"). Without it the
  app uses the real date.

### 4.2 `POST /__test__/seed` stores every field, or refuses

- Store **every** field the fixture gives. A field the app cannot store must be refused with
  **HTTP 400** and a message naming it — never silently ignored. A dropped field makes a test fail
  later on data it never received, which looks like a behaviour bug.
- When a later change makes a field mean something new, keep accepting it and give it its new
  meaning.

### 4.3 `GET /__test__/fixture` reads the data back

Returns what is stored, in the same shape and with the same field names the fixture used, so that
seeding a fixture and reading it back returns every field that was seeded, with the same value.

- Return each collection under the same key, with nested rows nested the same way (an invoice's
  `lineItems` inside the invoice).
- Return a field in the terms it was seeded in, even if the app stores it differently (a seeded
  `discountPct` comes back as `discountPct`, even if it is stored as a discount row).
- Extra fields and extra rows (defaults, derived values) are fine. After `POST /__test__/reset` the
  read-back is empty: `{}` or empty collections.

### 4.4 Earlier test data is replayed before your test

Before the first test runs, `bin/e2e` replays the test data that earlier checkpoints used — the data
only, never their tests. For each earlier fixture it resets, seeds the fixture, reads it back with
`GET /__test__/fixture`, and checks that every seeded field came back with the same value. It also
checks that a reset really empties the data.

If any of that fails, your test fails with a message that names the fixture and the field, for
example:

```
test-data contract: the seed did not keep clients[id=3].archived (sent true, read back <missing>)
  in the fixture of "archiving hides the client but keeps it"
```

Fix the seed or read-back so that field is stored again. Data from features that a change request
deliberately removes is left out of the replay.

How values are compared: numbers by value (`100`, `100.0` and `"100.00"` are equal); dates as
`yyyy-mm-dd`; rows that have an `id` are matched by `id`, and rows without one are matched by their
contents in any order.
