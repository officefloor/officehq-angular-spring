// Drives the app through its screens, binding only to data-testid (AGENTS.md §2). Every test
// arranges its own data with these helpers and asserts through them or through data-testid
// directly. An action helper returns what the screen reported: an id when a row was created,
// true/false for whether the action went through or the screen refused it (its error element
// appeared). It throws when neither a result nor an error shows within the wait, so a screen that
// does nothing fails the test instead of passing as "refused".
import { expect, test, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';

const AUDIT_FILE = process.env.AUDIT_FILE ?? '../.run/audit.log';
const WAIT = 8000;
const LOAD_WAIT = 30000;      // a sign-in, a sheet load or a today change: the whole sheet is re-read
const ERROR_GRACE = 2000;     // how long an error left over from the action before may linger
const AUDIT_WAIT = 3000;      // how long an audit line may lag the screen

const editing = new WeakMap<Page, string>();

export function sheetId(): string {
  return 's' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

/** The id a creating helper returned; throws when the screen refused (null) so the failure
 *  lands on the arranging line rather than on a later assertion. */
export function must(id: string | null): string {
  if (id === null) throw new Error('the screen refused this action (its error element appeared)');
  return id;
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** The trimmed text of a locator, read without waiting: '' when it is not on the page. A poll
 *  must never use an auto-waiting read (textContent, getAttribute) on an element that may be
 *  re-rendered or removed while it polls; that read would block until the test's own timeout. */
async function shown(loc: Locator): Promise<string> {
  return ((await loc.allTextContents())[0] ?? '').trim();
}

/** The ids carried by every element matching `loc`, read without waiting. */
async function ids(loc: Locator, prefix: string): Promise<string[]> {
  const out: string[] = [];
  for (const h of await loc.elementHandles()) out.push(idOf(await h.getAttribute('data-testid'), prefix));
  return out;
}

/** The rows matching `loc` whose id is not in `before`, in screen order, each with its id and the
 *  text of the named cells; read without waiting, so a poll may call it. A creating helper finds
 *  its row this way: a page may be showing the sheet as it was when it was loaded, so the rows
 *  other users added can land on it at any moment, including between a submit and its result. */
async function freshRows(loc: Locator, prefix: string, before: string[], cells: string[]): Promise<Record<string, string>[]> {
  const out: Record<string, string>[] = [];
  try {
    for (const h of await loc.elementHandles()) {
      const testid = (await h.getAttribute('data-testid')) ?? '';
      if (!testid.startsWith(prefix) || before.includes(testid.slice(prefix.length))) continue;
      const x: Record<string, string> = { id: testid.slice(prefix.length) };
      for (const c of cells) x[c] = ((await (await h.$(`[data-testid="${c}"]`))?.textContent()) ?? '').trim();
      out.push(x);
    }
  } catch {
    return [];                            // a row was re-rendered mid-read: the poll looks again
  }
  return out;
}

/** An amount as the screen shows it: two decimals, a dot, no grouping. */
function money(amount: string): string {
  const n = Number(amount.trim());
  return amount.trim() !== '' && Number.isFinite(n) ? n.toFixed(2) : amount;
}

/** The id of a person row new since `before` whose name is `name` (case and outer spaces
 *  ignored), or null when none shows yet. */
async function addedPerson(page: Page, before: string[], name: string): Promise<string | null> {
  const want = name.trim().toLowerCase();
  const hit = (await freshRows(rows(page), 'person-row-', before, ['person-name'])).find((p) => p['person-name'].toLowerCase() === want);
  return hit ? hit.id : null;
}

/** The id of a claim row under `scope` new since `before` showing the amount (and the date,
 *  when one was given) that was entered, or null when none shows yet; the first in screen order
 *  (newest first) when more than one does. */
async function addedClaim(scope: Locator, before: string[], f: ClaimFields): Promise<string | null> {
  const want = money(f.amount ?? '');
  const hit = (await freshRows(claimRows(scope), 'claim-row-', before, ['claim-amount', 'claim-date']))
    .find((c) => c['claim-amount'] === want && (f.date === undefined || c['claim-date'] === f.date));
  return hit ? hit.id : null;
}

function idOf(testid: string | null, prefix: string): string {
  if (!testid || !testid.startsWith(prefix)) throw new Error(`no ${prefix}* id on ${testid}`);
  return testid.slice(prefix.length);
}

/** Poll until `done` holds (true) or `error` is visible (false); throw when neither happens.
 *  Each check returns as soon as it can. An error already visible at the first look after the
 *  click may be left over from the action before (the screen clears it when a new action is
 *  submitted, see README): it counts as this action's refusal once it has gone away and been
 *  shown again, once its text changes, or once ERROR_GRACE has passed with no result. */
async function until(page: Page, done: () => Promise<boolean>, error: Locator, what: string): Promise<boolean> {
  const deadline = Date.now() + WAIT;
  let stale: string | null | undefined;   // text of an error visible at the first look; null if none
  let seenGone = false;
  const grace = Date.now() + ERROR_GRACE;
  while (Date.now() < deadline) {
    if (await done()) return true;
    const visible = await error.isVisible().catch(() => false);
    if (stale === undefined) stale = visible ? await shown(error) : null;
    if (visible) {
      if (stale === null || seenGone || (await shown(error)) !== stale || Date.now() >= grace) return false;
    } else {
      seenGone = true;
    }
    await page.waitForTimeout(50);
  }
  if (await done()) return true;
  if (await error.isVisible().catch(() => false)) return false;
  throw new Error(`${what}: neither a result nor an error appeared within ${WAIT}ms`);
}

async function sleep(ms: number): Promise<void> {
  await new Promise((r) => setTimeout(r, ms));
}

/** A counter the screen shows after a bulk action: the number in the cell once it is visible. */
async function counter(page: Page, cell: Locator, error: Locator, what: string): Promise<number | null> {
  const ok = await until(page, async () => (await cell.count()) > 0 && (await shown(cell)) !== '', error, what);
  return ok ? Number(await shown(cell)) : null;
}

// ---- session ---------------------------------------------------------------------------------

// Each sign-in gets its own browser context, so users on one sheet act independently. A context
// is closed once the test that opened it has finished: the test runner leaves them open, and a
// whole suite of open pages, each still running the app, would exhaust the machine.
const opened = new Map<BrowserContext, string>();   // context -> id of the test that opened it

export async function userPage(browser: Browser, user: string, sheet: string): Promise<Page> {
  let owner = '';
  try { owner = test.info().testId; } catch { /* outside a test: nothing to tidy by */ }
  for (const [c, t] of opened) {
    if (t === owner) continue;
    opened.delete(c);
    await c.close().catch(() => { /* already gone */ });
  }
  const context = await browser.newContext();
  opened.set(context, owner);
  const page = await context.newPage();
  page.setDefaultTimeout(LOAD_WAIT);     // an element an action needs that never shows fails here, not at the test's limit
  await page.goto('/');
  await page.getByTestId('signin-name').fill(user);
  await page.getByTestId('signin-submit').click();
  await expect(page.getByTestId('current-user')).toHaveText(user, { timeout: LOAD_WAIT });
  await loadSheet(page, sheet);
  return page;
}

export async function loadSheet(page: Page, sheet: string): Promise<void> {
  await page.getByTestId('sheet-id-input').fill(sheet);
  await page.getByTestId('sheet-load').click();
  await expect(page.getByTestId('sheet-current-id')).toHaveText(sheet, { timeout: LOAD_WAIT });
}

/** Re-load the active sheet so the screen shows what the store holds now. Loading the same id
 *  again is not observable (the current id already reads that), so the screen first loads a
 *  throwaway id, which has no one on it, and waits for the person rows to go; then it loads the
 *  sheet again and waits for rows to show. Rows that were on screen before may rightly not come
 *  back (someone retired since, say), so the wait is for any row, and for every row that is back
 *  to carry at least the claims it had; if none shows within the wait the screen is taken as it
 *  is and the test's own checks decide. The show-retired box and the as-of date, which belong to
 *  the screen, are put back if the loads reset them. */
export async function refresh(page: Page): Promise<void> {
  const sheet = (await page.getByTestId('sheet-current-id').textContent() ?? '').trim();
  const before = new Map<string, number>();
  for (const id of await ids(rows(page), 'person-row-')) before.set(id, await claimRows(row(page, id)).count());
  const retired = page.getByTestId('show-retired');
  const showRetiredWas = (await retired.count()) > 0 ? await retired.isChecked() : null;
  const asofWas = await shown(page.getByTestId('sheet-asof'));
  await loadSheet(page, sheet + '-reload');
  await expect.poll(() => rows(page).count(), { message: 'the throwaway sheet, which has no one on it, is on screen', timeout: LOAD_WAIT }).toBe(0);
  await loadSheet(page, sheet);
  if (showRetiredWas !== null && (await retired.isChecked()) !== showRetiredWas) await retired.setChecked(showRetiredWas);
  if (asofWas !== '' && (await shown(page.getByTestId('sheet-asof'))) !== asofWas) await setAsOf(page, asofWas);
  if (before.size === 0) return;
  const back = async () => {
    if ((await rows(page).count()) === 0) return false;
    for (const [id, n] of before) {
      const r = row(page, id);
      if ((await r.count()) > 0 && (await claimRows(r).count()) < n) return false;
    }
    return true;
  };
  const deadline = Date.now() + LOAD_WAIT;
  while (Date.now() < deadline && !(await back())) await page.waitForTimeout(50);
}

export async function setToday(page: Page, date: string): Promise<void> {
  await page.getByTestId('sheet-today-input').fill(date);
  await page.getByTestId('sheet-today-save').click();
  await expect(page.getByTestId('sheet-today')).toHaveText(date, { timeout: LOAD_WAIT });
}

/** Change the sheet's today where the screen may refuse it (an archived sheet, a viewer, an
 *  as-of view): true when the date took, false when the sheet's error element appeared. */
export async function setTodayTry(page: Page, date: string): Promise<boolean> {
  await page.getByTestId('sheet-today-input').fill(date);
  await page.getByTestId('sheet-today-save').click();
  return until(page, async () => (await shown(page.getByTestId('sheet-today'))) === date,
    page.getByTestId('sheet-error'), 'set today');
}

/** Archive the sheet: true once the screen shows it archived, false when refused. */
export async function archive(page: Page): Promise<boolean> {
  await page.getByTestId('sheet-archive').click();
  return until(page, async () => (await shown(page.getByTestId('sheet-archived'))) === 'yes',
    page.getByTestId('sheet-error'), 'archive');
}

/** Show the sheet as of a date (this user's screen only). */
export async function setAsOf(page: Page, date: string): Promise<void> {
  await page.getByTestId('asof-date').fill(date);
  await page.getByTestId('asof-set').click();
  await expect(page.getByTestId('sheet-asof')).toHaveText(date, { timeout: LOAD_WAIT });
}

export async function clearAsOf(page: Page): Promise<void> {
  await page.getByTestId('asof-clear').click();
  await expect(page.getByTestId('sheet-asof')).toHaveText('', { timeout: LOAD_WAIT });
}

// ---- people ----------------------------------------------------------------------------------

export function row(page: Page, personId: string): Locator {
  return page.getByTestId(`person-row-${personId}`);
}

function rows(page: Page): Locator {
  return page.locator('[data-testid^="person-row-"]');
}

function rowsNamed(page: Page, name: string): Locator {
  const re = new RegExp(`^\\s*${escapeRe(name.trim())}\\s*$`, 'i');
  return rows(page).filter({ has: page.getByTestId('person-name').filter({ hasText: re }) });
}

/** The id of the (newest) person row showing this name. */
export async function personId(page: Page, name: string): Promise<string> {
  const r = rowsNamed(page, name).last();
  await expect(r).toBeVisible({ timeout: WAIT });
  return idOf(await r.getAttribute('data-testid'), 'person-row-');
}

type PersonFields = { name?: string; grade?: string; gradeFrom?: string; terms?: string };

async function fillPersonForm(page: Page, f: PersonFields) {
  if (f.name !== undefined) await page.getByTestId('person-form-name').fill(f.name);
  if (f.grade !== undefined) await page.getByTestId('person-form-grade').selectOption(f.grade);
  if (f.gradeFrom !== undefined) await page.getByTestId('person-form-grade-from').fill(f.gradeFrom);
  if (f.terms !== undefined) await page.getByTestId('person-form-terms').fill(f.terms);
}

/** Add a person through the form. The new row's id, or null when the form refused. */
export async function addPerson(page: Page, f: PersonFields & { name: string; grade: string }): Promise<string | null> {
  const before = await ids(rows(page), 'person-row-');
  await fillPersonForm(page, f);
  await page.getByTestId('person-form-submit').click();
  let added: string | null = null;
  const ok = await until(page, async () => (added = await addedPerson(page, before, f.name)) !== null,
    page.getByTestId('person-form-error'), 'add person');
  return ok ? added : null;
}

export async function editOpen(page: Page, personId: string): Promise<void> {
  await page.getByTestId(`person-edit-${personId}`).click();
  await expect(page.getByTestId('person-form-name')).not.toHaveValue('', { timeout: WAIT });
  editing.set(page, personId);
}

/**
 * Save the open editor with these changes; true when saved, false when the form refused. `shows` is
 * the grade the row shows once saved: by default the grade entered, but a grade dated from another
 * day may leave the grade in force on the sheet's today unchanged.
 */
export async function editSave(page: Page, f: PersonFields, shows: string | undefined = f.grade): Promise<boolean> {
  const id = editing.get(page);
  if (!id) throw new Error('editSave without editOpen');
  await fillPersonForm(page, f);
  await page.getByTestId('person-form-submit').click();
  const r = row(page, id);
  return until(page, async () => {
    if (f.name !== undefined && await shown(r.getByTestId('person-name')) !== f.name.trim()) return false;
    if (shows !== undefined && await shown(r.getByTestId('person-grade')) !== shows) return false;
    if (f.terms !== undefined && await shown(r.getByTestId('person-terms')) !== f.terms) return false;
    return !(await page.getByTestId('person-form-error').isVisible().catch(() => false));
  }, page.getByTestId('person-form-error'), 'save person');
}

export async function editPerson(page: Page, personId: string, f: PersonFields, shows?: string): Promise<boolean> {
  await editOpen(page, personId);
  return editSave(page, f, shows);
}

export async function retire(page: Page, personId: string, date: string): Promise<boolean> {
  const r = row(page, personId);
  await r.getByTestId('person-retire-date').fill(date);
  await r.getByTestId('person-retire').click();
  return until(page, async () => (await r.count()) === 0
    || await shown(r.getByTestId('person-retired')) === 'yes',
    r.getByTestId('person-error'), 'retire');
}

/** Take a statement for the person: true once the row's statement count has moved on. */
export async function statement(page: Page, personId: string): Promise<boolean> {
  const r = row(page, personId);
  const was = await shown(r.getByTestId('person-statement-count'));
  await r.getByTestId('person-statement').click();
  return until(page, async () => await shown(r.getByTestId('person-statement-count')) !== was,
    r.getByTestId('person-error'), 'statement');
}

/** Take the person's statement as of a date: true once the statement count has moved on. */
export async function statementAsOf(page: Page, personId: string, date: string): Promise<boolean> {
  const r = row(page, personId);
  const was = await shown(r.getByTestId('person-statement-count'));
  await r.getByTestId('person-statement-asof').fill(date);
  await r.getByTestId('person-statement').click();
  return until(page, async () => await shown(r.getByTestId('person-statement-count')) !== was,
    r.getByTestId('person-error'), 'statement as of');
}

/** Cancel the person's last statement: true once the statement count has moved on. */
export async function unstate(page: Page, personId: string): Promise<boolean> {
  const r = row(page, personId);
  const was = await shown(r.getByTestId('person-statement-count'));
  await r.getByTestId('person-unstate').click();
  return until(page, async () => await shown(r.getByTestId('person-statement-count')) !== was,
    r.getByTestId('person-error'), 'cancel statement');
}

/** Toggle the hold (no dates), or add a hold with dates: true once the row shows it. */
export async function hold(page: Page, personId: string, f: { from?: string; to?: string }): Promise<boolean> {
  const r = row(page, personId);
  const dated = f.from !== undefined || f.to !== undefined || (await r.getByTestId('person-hold-from').count()) > 0;
  const wasText = await shown(r.getByTestId('person-hold'));
  const lines = r.getByTestId('person-hold-line');
  const wasLines = await lines.count();
  if (f.from !== undefined) await r.getByTestId('person-hold-from').fill(f.from);
  if (f.to !== undefined) await r.getByTestId('person-hold-to').fill(f.to);
  await r.getByTestId('person-hold-toggle').click();
  return until(page, async () => dated
    ? (await lines.count()) > wasLines
    : await shown(r.getByTestId('person-hold')) !== wasText,
    r.getByTestId('person-error'), 'hold');
}

/** Start (true) or stop (false) watching the person as the signed-in user. */
export async function watch(page: Page, personId: string, on: boolean): Promise<boolean> {
  const r = row(page, personId);
  await r.getByTestId('person-watch').click();
  return until(page, async () => (await shown(r.getByTestId('person-watched'))) === (on ? 'yes' : 'no'),
    r.getByTestId('person-error'), 'watch');
}

export async function showRetired(page: Page, on: boolean): Promise<void> {
  await page.getByTestId('show-retired').setChecked(on);
}

export async function addPatron(page: Page, personId: string, patronName: string): Promise<boolean> {
  const r = row(page, personId);
  const names = async () => (await shown(r.getByTestId('person-patrons'))).split(',').filter((s) => s.trim()).length;
  const before = await names();
  try {
    await r.getByTestId('person-patron-select').selectOption({ label: patronName }, { timeout: 2000 });
  } catch {
    return false;             // not offered: the screen refuses it by leaving it out
  }
  await r.getByTestId('person-patron-add').click();
  return until(page, async () => (await names()) > before, r.getByTestId('person-patron-error'), 'add patron');
}

/** Give the selected people a grade together: how many were changed. */
export async function bulkGrade(page: Page, personIds: string[], f: { grade: string; from?: string }): Promise<number | null> {
  for (const id of personIds) await row(page, id).getByTestId('person-select').setChecked(true);
  await page.getByTestId('bulk-grade').selectOption(f.grade);
  if (f.from !== undefined) await page.getByTestId('bulk-grade-from').fill(f.from);
  const was = await shown(page.getByTestId('bulk-graded'));
  await page.getByTestId('bulk-grade-apply').click();
  await page.waitForTimeout(300);
  const ok = await until(page, async () => (await page.getByTestId('bulk-graded').count()) > 0
    && (await shown(page.getByTestId('bulk-graded'))) !== '' && ((await shown(page.getByTestId('bulk-graded'))) !== was || was === ''),
    page.getByTestId('person-error'), 'bulk grade');
  return ok ? Number(await shown(page.getByTestId('bulk-graded'))) : null;
}

// ---- claims ----------------------------------------------------------------------------------

export function claimRow(page: Page, claimId: string): Locator {
  return page.getByTestId(`claim-row-${claimId}`);
}

function claimRows(scope: Locator | Page): Locator {
  return scope.locator('[data-testid^="claim-row-"]');
}

function personRowOf(page: Page, claimId: string): Locator {
  return rows(page).filter({ has: claimRow(page, claimId) });
}

export async function claimCount(page: Page, personId: string): Promise<number> {
  return claimRows(row(page, personId)).count();
}

/** The id of the person's claim at position `idx` in creation order (claims are listed newest
 *  first, so the oldest is the last row). */
export async function claimAt(page: Page, personId: string, idx: number): Promise<string> {
  const all = claimRows(row(page, personId));
  const n = await all.count();
  const r = all.nth(n - 1 - idx);
  await expect(r).toBeVisible({ timeout: WAIT });
  return idOf(await r.getAttribute('data-testid'), 'claim-row-');
}

type ClaimFields = { amount?: string; date?: string; note?: string; corrects?: string };

async function fillClaimForm(r: Locator, f: ClaimFields) {
  if (f.amount !== undefined) await r.getByTestId('claim-form-amount').fill(f.amount);
  if (f.date !== undefined) await r.getByTestId('claim-form-date').fill(f.date);
  if (f.note !== undefined) await r.getByTestId('claim-form-note').fill(f.note);
  if (f.corrects !== undefined) await r.getByTestId('claim-form-corrects').fill(f.corrects);
}

/** Add a claim from the person's row. The new claim's id, or null when refused. */
export async function addClaim(page: Page, personId: string, f: ClaimFields & { amount: string }): Promise<string | null> {
  const r = row(page, personId);
  const before = await ids(claimRows(r), 'claim-row-');
  await fillClaimForm(r, f);
  await r.getByTestId('claim-form-submit').click();
  let added: string | null = null;
  const ok = await until(page, async () => (added = await addedClaim(r, before, f)) !== null,
    r.getByTestId('claim-form-error'), 'add claim');
  return ok ? added : null;
}

/** Submit the claim form twice in quick succession (a double click on submit). */
export async function addClaimTwice(page: Page, personId: string, f: ClaimFields & { amount: string }): Promise<string | null> {
  const r = row(page, personId);
  const before = await ids(claimRows(r), 'claim-row-');
  await fillClaimForm(r, f);
  await r.getByTestId('claim-form-submit').dblclick();
  const ok = await until(page, async () => (await addedClaim(r, before, f)) !== null,
    r.getByTestId('claim-form-error'), 'add claim twice');
  await page.waitForTimeout(500);          // let a second record land, if the app stores one
  return ok ? addedClaim(r, before, f) : null;
}

/** Paste lines of claims into the paste box: how many were added, or null when refused. */
export async function pasteClaims(page: Page, text: string): Promise<number | null> {
  const before = await claimRows(page).count();
  await page.getByTestId('paste-claims').fill(text);
  await page.getByTestId('paste-submit').click();
  const ok = await until(page, async () => (await claimRows(page).count()) > before,
    page.getByTestId('paste-error'), 'paste claims');
  return ok ? Number(await shown(page.getByTestId('paste-added'))) : null;
}

/** Edit a claim through its edit button and the claim form; true when the row shows the change. */
export async function editClaim(page: Page, claimId: string, f: ClaimFields): Promise<boolean> {
  const c = claimRow(page, claimId);
  const r = personRowOf(page, claimId);
  await c.getByTestId('claim-edit').click();
  if (await c.getByTestId('claim-error').isVisible().catch(() => false)) return false;
  await fillClaimForm(r, f);
  await r.getByTestId('claim-form-submit').click();
  const error = c.getByTestId('claim-error').or(r.getByTestId('claim-form-error'));
  return until(page, async () => {
    for (const [k, v] of Object.entries(f)) {
      const cell = await shown(c.getByTestId(`claim-${k}`));
      if (cell !== v) return false;
    }
    return true;
  }, error, 'edit claim');
}

/** Open a claim's editor (the edit button) and leave it open: true when the form filled with
 *  the claim's values, false when the claim refused (not editable, or locked by another user). */
export async function claimEditOpen(page: Page, claimId: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const r = personRowOf(page, claimId);
  const amount = await shown(c.getByTestId('claim-amount'));
  await c.getByTestId('claim-edit').click();
  return until(page, async () => (await r.getByTestId('claim-form-amount').inputValue().catch(() => '')) === amount
    && !(await c.getByTestId('claim-error').isVisible().catch(() => false)),
    c.getByTestId('claim-error'), 'open claim editor');
}

/** Save the claim editor opened with claimEditOpen: true when the row shows the change. */
export async function claimEditSave(page: Page, claimId: string, f: ClaimFields): Promise<boolean> {
  const c = claimRow(page, claimId);
  const r = personRowOf(page, claimId);
  await fillClaimForm(r, f);
  await r.getByTestId('claim-form-submit').click();
  const error = c.getByTestId('claim-error').or(r.getByTestId('claim-form-error'));
  return until(page, async () => {
    for (const [k, v] of Object.entries(f)) {
      if (await shown(c.getByTestId(`claim-${k}`)) !== v) return false;
    }
    return true;
  }, error, 'save claim editor');
}

export async function claimEditCancel(page: Page, claimId: string): Promise<void> {
  await claimRow(page, claimId).getByTestId('claim-cancel').click();
}

/** open | settle | void | approve | reject(reason): true when the status changed. */
export async function claimAction(page: Page, claimId: string, action: string, reason?: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const was = await shown(c.getByTestId('claim-status'));
  if (reason !== undefined) await c.getByTestId('claim-reject-reason').fill(reason);
  await c.getByTestId(`claim-${action}`).click();
  return until(page, async () => await shown(c.getByTestId('claim-status')) !== was,
    c.getByTestId('claim-error'), `claim ${action}`);
}

/** Re-settle a settled claim: true once its status log gained a line. */
export async function resettle(page: Page, claimId: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const lines = c.getByTestId('claim-log-line');
  const was = await lines.count();
  await c.getByTestId('claim-resettle').click();
  return until(page, async () => (await lines.count()) > was, c.getByTestId('claim-error'), 're-settle');
}

/** Defer an open claim until a date: true once the row shows the date. */
export async function defer(page: Page, claimId: string, until_: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  await c.getByTestId('claim-defer-date').fill(until_);
  await c.getByTestId('claim-defer').click();
  return until(page, async () => (await shown(c.getByTestId('claim-deferred-until'))) === until_,
    c.getByTestId('claim-error'), 'defer');
}

/** Raise the claim's proofs count by one: true once the count moved. */
export async function addProof(page: Page, claimId: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const was = await shown(c.getByTestId('claim-proofs'));
  await c.getByTestId('claim-proof').click();
  return until(page, async () => (await shown(c.getByTestId('claim-proofs'))) !== was,
    c.getByTestId('claim-error'), 'add proof');
}

/** Re-date a claim: true once the row shows the new date. */
export async function redate(page: Page, claimId: string, date: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  await c.getByTestId('claim-date-new').fill(date);
  await c.getByTestId('claim-redate').click();
  return until(page, async () => (await shown(c.getByTestId('claim-date'))) === date,
    c.getByTestId('claim-error'), 'redate');
}

/** Split an amount off an open claim: the new claim's id, or null when refused. */
export async function split(page: Page, personId: string, claimId: string, amount: string): Promise<string | null> {
  const r = row(page, personId);
  const c = claimRow(page, claimId);
  const before = await ids(claimRows(r), 'claim-row-');
  await c.getByTestId('claim-split-amount').fill(amount);
  await c.getByTestId('claim-split').click();
  let added: string | null = null;
  const ok = await until(page, async () => (added = await addedClaim(r, before, { amount })) !== null,
    c.getByTestId('claim-error'), 'split');
  return ok ? added : null;
}

export async function numbersOf(page: Page, claimIds: string[]): Promise<string[]> {
  await refresh(page);
  const out: string[] = [];
  for (const id of claimIds) {
    await expect(claimRow(page, id)).toBeVisible({ timeout: WAIT });
    out.push(await shown(claimRow(page, id).getByTestId('claim-number')));
  }
  return out;
}

export async function reassign(page: Page, claimId: string, toName: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  await c.getByTestId('claim-reassign-to').selectOption({ label: toName });
  await c.getByTestId('claim-reassign').click();
  return until(page, async () => (await rowsNamed(page, toName).filter({ has: claimRow(page, claimId) }).count()) > 0,
    c.getByTestId('claim-error'), 'reassign');
}

export async function bulkOpen(page: Page, claimIds: string[]): Promise<{ opened: number; refused: number }> {
  for (const id of claimIds) await claimRow(page, id).getByTestId('claim-select').setChecked(true);
  await page.getByTestId('bulk-open').click();
  await expect(page.getByTestId('bulk-opened')).toBeVisible({ timeout: WAIT });
  return {
    opened: Number(await shown(page.getByTestId('bulk-opened'))),
    refused: Number(await shown(page.getByTestId('bulk-refused'))),
  };
}

export async function bulkSettle(page: Page, claimIds: string[]): Promise<{ settled: number; refused: number }> {
  for (const id of claimIds) await claimRow(page, id).getByTestId('claim-select').setChecked(true);
  await page.getByTestId('bulk-settle').click();
  await expect(page.getByTestId('bulk-settled')).toBeVisible({ timeout: WAIT });
  return {
    settled: Number(await shown(page.getByTestId('bulk-settled'))),
    refused: Number(await shown(page.getByTestId('bulk-settle-refused'))),
  };
}

/** The quick-add bar: the new claim's id, or null when refused. */
export async function quickAdd(page: Page, f: { name: string; amount: string }): Promise<string | null> {
  const before = await ids(claimRows(page), 'claim-row-');
  await page.getByTestId('quick-name').fill(f.name);
  await page.getByTestId('quick-amount').fill(f.amount);
  await page.getByTestId('quick-submit').click();
  let added: string | null = null;
  const ok = await until(page, async () => (added = await addedClaim(rowsNamed(page, f.name), before, { amount: f.amount })) !== null,
    page.getByTestId('quick-error'), 'quick add');
  return ok ? added : null;
}

// ---- rates, periods, settings, roles, export -------------------------------------------------

export async function setRate(page: Page, f: { grade: string; pct: string; from?: string; to?: string }): Promise<boolean> {
  await page.getByTestId('rate-form-grade').selectOption(f.grade);
  await page.getByTestId('rate-form-pct').fill(f.pct);
  if (f.from !== undefined) await page.getByTestId('rate-form-from').fill(f.from);
  if (f.to !== undefined) await page.getByTestId('rate-form-to').fill(f.to);
  await page.getByTestId('rate-form-submit').click();
  const r = page.getByTestId(`rate-row-${f.grade}-${f.from ?? 'start'}`);
  return until(page, async () => await shown(r.getByTestId('rate-pct')) === f.pct
    && (f.to === undefined || await shown(r.getByTestId('rate-to')) === f.to),
    page.getByTestId('rate-form-error'), 'set rate');
}

export async function addPeriod(page: Page, f: { name: string; from: string; to: string }): Promise<boolean> {
  const all = page.locator('[data-testid^="period-row-"]');
  const before = await all.count();
  await page.getByTestId('period-form-name').fill(f.name);
  await page.getByTestId('period-form-from').fill(f.from);
  await page.getByTestId('period-form-to').fill(f.to);
  await page.getByTestId('period-form-submit').click();
  return until(page, async () => (await all.count()) > before, page.getByTestId('period-form-error'), 'add period');
}

/** Generate monthly periods between two dates: how many were made, or null when refused. */
export async function generatePeriods(page: Page, from: string, to: string): Promise<number | null> {
  await page.getByTestId('period-generate-from').fill(from);
  await page.getByTestId('period-generate-to').fill(to);
  const was = await shown(page.getByTestId('period-generated'));
  await page.getByTestId('period-generate').click();
  await page.waitForTimeout(300);
  const ok = await until(page, async () => (await page.getByTestId('period-generated').count()) > 0
    && (await shown(page.getByTestId('period-generated'))) !== '' && ((await shown(page.getByTestId('period-generated'))) !== was || was === ''),
    page.getByTestId('period-generate-error'), 'generate periods');
  return ok ? Number(await shown(page.getByTestId('period-generated'))) : null;
}

/** close | soft-close | reopen: true when the period's closed flag changed. */
export async function periodAction(page: Page, name: string, action: string): Promise<boolean> {
  const r = page.getByTestId(`period-row-${name}`);
  const was = await shown(r.getByTestId('period-closed'));
  await r.getByTestId(`period-${action}`).click();
  return until(page, async () => await shown(r.getByTestId('period-closed')) !== was,
    r.getByTestId('period-error'), `period ${action}`);
}

export async function setSetting(page: Page, key: string, value: string): Promise<void> {
  await page.getByTestId(`setting-${key}`).fill(value);
  await page.getByTestId('settings-save').click();
  await expect(page.getByTestId(`setting-${key}-current`)).toHaveText(value, { timeout: WAIT });
}

/** Save a setting where the screen may refuse it: true when the value in force shows it. */
export async function setSettingTry(page: Page, key: string, value: string): Promise<boolean> {
  await page.getByTestId(`setting-${key}`).fill(value);
  await page.getByTestId('settings-save').click();
  return until(page, async () => (await shown(page.getByTestId(`setting-${key}-current`))) === value,
    page.getByTestId('settings-error'), 'save setting');
}

/** Save a setting from a date; `shows` is the value in force on the sheet's today once saved
 *  (a from-date after today leaves the current value as it was). */
export async function setSettingDated(page: Page, key: string, value: string, from: string, shows: string): Promise<boolean> {
  await page.getByTestId(`setting-${key}`).fill(value);
  await page.getByTestId(`setting-${key}-from`).fill(from);
  await page.getByTestId('settings-save').click();
  const ok = await until(page, async () => (await shown(page.getByTestId(`setting-${key}-current`))) === shows
    && !(await page.getByTestId('settings-error').isVisible().catch(() => false)),
    page.getByTestId('settings-error'), 'save dated setting');
  if (ok) await page.waitForTimeout(300);     // the value may already have shown; let the save land
  return ok;
}

export async function setRole(page: Page, user: string, role: string): Promise<boolean> {
  await page.getByTestId('role-form-user').fill(user);
  await page.getByTestId('role-form-role').fill(role);
  await page.getByTestId('role-form-submit').click();
  const r = page.getByTestId(`role-row-${user}`);
  return until(page, async () => await shown(r.getByTestId('role-value')) === role,
    page.getByTestId('role-form-error'), 'set role');
}

/** Delegate the steward role: true once a delegation line appeared. */
export async function delegate(page: Page, f: { user: string; from: string; to: string }): Promise<boolean> {
  const lines = page.getByTestId('delegate-line');
  const was = await lines.count();
  await page.getByTestId('delegate-user').fill(f.user);
  await page.getByTestId('delegate-from').fill(f.from);
  await page.getByTestId('delegate-to').fill(f.to);
  await page.getByTestId('delegate-submit').click();
  return until(page, async () => (await lines.count()) > was, page.getByTestId('delegate-error'), 'delegate');
}

export async function exportCsv(page: Page): Promise<string> {
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('export-people').click()]);
  const path = await download.path();
  if (!path) throw new Error('export produced no file');
  return readFileSync(path, 'utf8');
}

// ---- audit -----------------------------------------------------------------------------------

/** The audit lines written for this sheet, in file order. */
export function auditFor(sheet: string): string[] {
  if (!existsSync(AUDIT_FILE)) return [];
  return readFileSync(AUDIT_FILE, 'utf8').split('\n').filter((l) => l.includes(` sheet=${sheet} `));
}

/** The sheet's audit lines are exactly these, in order; a line may carry further fields after
 *  the expected ones (a later feature may extend a line, never change or reorder its fields).
 *  A line may land a moment after the screen shows its result, so the file is re-read until
 *  it has as many lines as expected (lines are only ever appended), for AUDIT_WAIT at most. */
export async function expectAudit(sheet: string, expected: string[]): Promise<void> {
  let lines = auditFor(sheet);
  const deadline = Date.now() + AUDIT_WAIT;
  while (lines.length < expected.length && Date.now() < deadline) {
    await sleep(50);
    lines = auditFor(sheet);
  }
  expect(lines.length, `audit lines for ${sheet}:\n${lines.join('\n')}`).toBe(expected.length);
  lines.forEach((line, i) => {
    const e = expected[i];
    const ok = line === e || line.startsWith(e + ' ');
    expect(ok, `audit line ${i + 1}\n  expected: ${e}\n  actual:   ${line}`).toBe(true);
  });
}
