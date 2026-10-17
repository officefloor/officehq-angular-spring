# Working in this app

## 1. How you work

- Implement the ONE change request you have been given. A single change may span the stored data
  and its schema, the server, and the front end.
- **Read `DESIGN.md` first.** It describes how this app is built — its stack, layout and conventions
  — and how to do things in it. Follow it.
- Your change is checked by an automated Playwright test that drives the running app through its
  UI. The test is in `e2e/specs/`. **Run `bin/e2e`** to build the app, start it, run the test and
  stop it — run it as you work and iterate until it passes.
- **Do not edit** the test, `bin/*`, `e2e/support/*`, `AGENTS.md`, `CLAUDE.md` or `DESIGN.md`.

The rest of this file is how the app is tested.

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

A fixture is a JSON object. Each top-level key is a collection of rows (`people`, `patrons`, ...),
a settings object, or a single value:

```json
{
  "anchor": "2026-02-05",
  "people":  [{ "id": 1, "name": "Dana", "alpha": 3, "beta": 4, "gamma": 10 }],
  "patrons": [{ "personId": 1, "patronId": 2 }]
}
```

- Rows carry the ids the test then uses in its `data-testid`s (`person-row-1`). Store them as given.
- A field means the same thing everywhere it appears. Store the value you are given; do not
  reinterpret it. A relationship collection links rows by their ids (`patrons` links a person to
  the people who are their patrons).
- **A field a fixture leaves out takes its default.** The default must be the ordinary value a newly
  created record would get (a missing attribute is absent, not zero).
- **`anchor` is the sheet's reference date.** When a fixture gives `anchor`, the app must treat that
  date as the fixed point for everything date-dependent (for example a person's age from their onset
  date). Without it the app uses the real date.

### 4.2 `POST /__test__/seed` stores every field, or refuses

- Store **every** field the fixture gives. A field the app cannot store must be refused with
  **HTTP 400** and a message naming it — never silently ignored. A dropped field makes a test fail
  later on data it never received, which looks like a behaviour bug.
- When a later change makes a field mean something new, keep accepting it and give it its new
  meaning.

### 4.3 `GET /__test__/fixture` reads the data back

Returns what is stored, in the same shape and with the same field names the fixture used, so that
seeding a fixture and reading it back returns every field that was seeded, with the same value.

- Return each collection under the same key. A relationship collection comes back under its own key
  (`patrons` as `patrons`), and any nested rows nested the same way they were seeded.
- Return a field in the terms it was seeded in, even if the app stores it differently (a seeded
  attribute comes back under the same name, even if it is stored in another shape).
- Extra fields and extra rows (defaults, derived values) are fine. After `POST /__test__/reset` the
  read-back is empty: `{}` or empty collections.

### 4.4 The stored test data is checked before your test

The app's test data sets are listed in `.run/fixture-contract.json`. Before the first test runs,
`bin/e2e` checks them: for each data set it resets, seeds it, reads it back with
`GET /__test__/fixture`, and checks that every seeded field came back with the same value. It also
checks that a reset really empties the data. It uses only the data, never any test.

If any of that fails, your test fails with a message that names the data set and the field, for
example:

```
test-data contract: the seed did not keep people[id=3].alpha (sent 5, read back <missing>)
  in the data set of "momentum updates when a person's alpha changes"
```

Fix the seed or read-back so that field is stored again. Data for a feature that a change request
removes is taken out of the list.

How values are compared: numbers by value (`100`, `100.0` and `"100.00"` are equal); dates as
`yyyy-mm-dd`; rows that have an `id` are matched by `id`, and rows without one are matched by their
contents in any order.
