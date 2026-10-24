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
- Everything is on the one screen at `/`. A test never navigates anywhere else.

## 3. Tests arrange their data through the screens

There is no test endpoint and no shared data set. Every test signs in, loads a sheet of its own
(a fresh id), and creates everything it needs through the same screens a person would use, with
the helpers in `e2e/support/ui.ts`. Read that file: it is the exact way the test presses the
screens, and it relies on the behaviour below.

- **A sheet is loaded by id.** Loading an id that was never used starts an empty sheet. Loading an
  id again shows what the store holds for it now, re-read from the store (the test re-loads a sheet
  to see what another signed-in user changed). Everything is kept per sheet.
- **Rows carry their id in the `data-testid`.** A person's row is `person-row-<id>`, a claim's row
  `claim-row-<id>`, with ids the app assigns. The test finds a new person's row by the name it
  shows (`person-name`), and a new claim as the first `claim-row-*` under its person: claims are
  listed **newest first**.
- **A refused action shows an error element, and changes nothing.** Each form and each action has
  its own error `data-testid` (listed in the test). The test decides "went through" or "refused"
  by whether the row or value it expects appears or that error element becomes visible — so a
  refusal must show the error, and an action that succeeds must not leave an old error showing:
  an error is removed as soon as the form it belongs to is changed or submitted again, or the next
  action on that row is taken. A refused change writes nothing to the store and nothing to the
  audit file. A refused save of a claim's editor leaves the editor open; the test cancels it
  (the cancel button) before its next action on that person.
- **The test fills every field of a form it submits**, clearing the ones it leaves out, so a value
  typed for a refused submission never carries into the next one; a field left blank
  takes its default (a claim's date is the sheet's today, a from-date is the sheet's today).
- **A result count is cleared when its button is pressed again.** A button that reports how many
  it added, opened, made or set (an import, a paste, a bulk action, a generate) shows the new
  count once the action is done; while the action runs, the old count is gone. Two presses with
  the same result must still be told apart.
- **A screen that does neither fails the test.** If after an action neither the expected result nor
  the error element appears within a few seconds, the test fails on that step.
- **Two users at once.** A test may open a second browser context, sign in as another name, and act
  on the same sheet. What one user changes, the other sees after re-loading the sheet.

## 4. Side effects are tested through the audit file

Behaviour that is not visible on screen is written to the audit file, one record per line, through
the app's audit service, appended in the order the changes happen. `bin/start` and `bin/e2e` set
its path in `AUDIT_FILE`; tests read it with `e2e/support/ui.ts` and look only at the lines carrying
their own sheet id (`sheet=<id>`), so the file is never cleared. Write records exactly in the form
your test expects: the event name, then `sheet=<id>` and `by=<user>`, then the fields in the order
the request gives, separated by single spaces. A test matches a line by its start, so a field a
later change adds goes at the **end** of the line; never change or reorder the fields already
there.
