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

/** A name as a name cell shows it, without a trailing " (<grade>)" the screen may append. */
function plainName(shown: string): string {
  return shown.trim().replace(/\s\([^()]*\)$/, '');
}

/** The id of a person row new since `before` whose name is `name` (case and outer spaces
 *  ignored), or null when none shows yet. */
async function addedPerson(page: Page, before: string[], name: string): Promise<string | null> {
  const want = name.trim().toLowerCase();
  const hit = (await freshRows(rows(page), 'person-row-', before, ['person-name'])).find((p) => plainName(p['person-name']).toLowerCase() === want);
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

/** The id of the first claim row under `scope` new since `before`, or null when none shows yet. */
async function addedAnyClaim(scope: Locator, before: string[]): Promise<string | null> {
  const hit = (await freshRows(claimRows(scope), 'claim-row-', before, []))[0];
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
 *  shown again, once its text changes, or once ERROR_GRACE has passed with no result.
 *  `already` says `done` held BEFORE the action (watching a person already watched, saving a
 *  setting at its current value): then the result on screen proves nothing, and the action
 *  counts as taken only once ERROR_GRACE has passed with no refusal shown. */
async function until(page: Page, done: () => Promise<boolean>, error: Locator, what: string,
                     already = false): Promise<boolean> {
  const deadline = Date.now() + WAIT;
  let stale: string | null | undefined;   // text of an error visible at the first look; null if none
  let seenGone = false;
  const grace = Date.now() + ERROR_GRACE;
  while (Date.now() < deadline) {
    if (!already && await done()) return true;
    const visible = await error.isVisible().catch(() => false);
    if (stale === undefined) stale = visible ? await shown(error) : null;
    if (visible) {
      if (stale === null || seenGone || (await shown(error)) !== stale || Date.now() >= grace) return false;
    } else {
      seenGone = true;
    }
    // the state held before the action: an error still showing at the grace outranks it
    if (already && Date.now() >= grace && await done()) return true;
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

/** Fill a form field, or CLEAR it when the test gives no value. A refused submission leaves what
 *  was typed in the form (nothing in the contract says otherwise), so a value left over from the
 *  attempt before must never leak into this one: the test states every field it submits. A field
 *  the screen does not have is skipped. */
async function setField(scope: Locator | Page, testid: string, value: string | undefined): Promise<void> {
  const loc = scope.getByTestId(testid);
  if (value !== undefined) {
    await loc.fill(value);
  } else if ((await loc.count()) > 0) {
    await loc.fill('');
  }
}

/** The result count a button shows once its action is done (how many were added, made, opened).
 *  `was` is what the cell showed before the click. The count is taken once it differs from `was`,
 *  or once the cell was seen cleared and shown again (README: a result is cleared when its button
 *  is pressed again). Two identical results in a row can look the same on screen for the whole
 *  wait: then, with no error showing and a value in the cell, that value is taken rather than
 *  failing a step the app did take. */
async function countAfter(page: Page, cell: Locator, error: Locator, what: string, was: string): Promise<string | null> {
  let cleared = false;
  const done = async () => {
    if ((await cell.count()) === 0) return false;
    const now = await shown(cell);
    if (now === '') { cleared = true; return false; }
    return now !== was || was === '' || cleared;
  };
  try {
    return (await until(page, done, error, what)) ? await shown(cell) : null;
  } catch (e) {
    if (await error.isVisible().catch(() => false)) return null;
    if ((await cell.count()) > 0 && (await shown(cell)) !== '') return shown(cell);
    throw e;
  }
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
  const previewWas = await shown(page.getByTestId('sheet-preview'));
  await loadSheet(page, sheet + '-reload');
  await expect.poll(() => rows(page).count(), { message: 'the throwaway sheet, which has no one on it, is on screen', timeout: LOAD_WAIT }).toBe(0);
  await loadSheet(page, sheet);
  if (showRetiredWas !== null && (await retired.isChecked()) !== showRetiredWas) await retired.setChecked(showRetiredWas);
  if (asofWas !== '' && (await shown(page.getByTestId('sheet-asof'))) !== asofWas) await setAsOf(page, asofWas);
  if (previewWas !== '' && (await shown(page.getByTestId('sheet-preview'))) !== previewWas) await setPreview(page, previewWas);
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
  const done = async () => (await shown(page.getByTestId('sheet-today'))) === date;
  const already = await done();
  await page.getByTestId('sheet-today-input').fill(date);
  await page.getByTestId('sheet-today-save').click();
  return until(page, done, page.getByTestId('sheet-error'), 'set today', already);
}

/** Archive the sheet: true once the screen shows it archived, false when refused. */
export async function archive(page: Page): Promise<boolean> {
  const done = async () => (await shown(page.getByTestId('sheet-archived'))) === 'yes';
  const already = await done();
  await page.getByTestId('sheet-archive').click();
  return until(page, done, page.getByTestId('sheet-error'), 'archive', already);
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
  const re = new RegExp(`^\\s*${escapeRe(name.trim())}(\\s\\([^()]*\\))?\\s*$`, 'i');
  return rows(page).filter({ has: page.getByTestId('person-name').filter({ hasText: re }) });
}

/** The id of the (newest) person row showing this name. */
export async function personId(page: Page, name: string): Promise<string> {
  const r = rowsNamed(page, name).last();
  await expect(r).toBeVisible({ timeout: WAIT });
  return idOf(await r.getAttribute('data-testid'), 'person-row-');
}

type PersonFields = { name?: string; grade?: string; gradeFrom?: string; terms?: string; termsFrom?: string; contact?: string; tags?: string };

/** Fill the person form. `clear` (an add) also empties every optional field the test does not
 *  give, so nothing typed for a refused add carries into this one; an edit leaves the
 *  fields it is not given as the editor filled them. */
async function fillPersonForm(page: Page, f: PersonFields, clear = false) {
  if (f.name !== undefined) await page.getByTestId('person-form-name').fill(f.name);
  if (f.grade !== undefined) await page.getByTestId('person-form-grade').selectOption(f.grade);
  for (const [key, testid] of [['gradeFrom', 'person-form-grade-from'], ['terms', 'person-form-terms'],
    ['termsFrom', 'person-form-terms-from'], ['contact', 'person-form-contact'], ['tags', 'person-form-tags']] as const) {
    const v = f[key];
    if (v !== undefined) await page.getByTestId(testid).fill(v);
    else if (clear) await setField(page, testid, undefined);
  }
}

/** Add a person through the form. The new row's id, or null when the form refused. */
export async function addPerson(page: Page, f: PersonFields & { name: string; grade: string }): Promise<string | null> {
  const before = await ids(rows(page), 'person-row-');
  await fillPersonForm(page, f, true);
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
type Shows = string | { grade?: string; terms?: string };

export async function editSave(page: Page, f: PersonFields, shows?: Shows): Promise<boolean> {
  const id = editing.get(page);
  if (!id) throw new Error('editSave without editOpen');
  const want = typeof shows === 'string' ? { grade: shows } : (shows ?? {});
  const grade = want.grade ?? f.grade;                       // the grade the row shows once saved
  const terms = want.terms ?? f.terms;                       // the terms in force the row shows
  const r = row(page, id);
  const shows_ = async () => {
    if (f.name !== undefined && plainName(await shown(r.getByTestId('person-name'))) !== f.name.trim()) return false;
    if (grade !== undefined && await shown(r.getByTestId('person-grade')) !== grade) return false;
    if (terms !== undefined && await shown(r.getByTestId('person-terms')) !== terms) return false;
    return !(await page.getByTestId('person-form-error').isVisible().catch(() => false));
  };
  // a save the row cannot show (the values it checks are already there) is judged by its error
  const already = await shows_();
  await fillPersonForm(page, f);
  await page.getByTestId('person-form-submit').click();
  return until(page, shows_, page.getByTestId('person-form-error'), 'save person', already);
}

export async function editPerson(page: Page, personId: string, f: PersonFields, shows?: Shows): Promise<boolean> {
  await editOpen(page, personId);
  return editSave(page, f, shows);
}

export async function retire(page: Page, personId: string, date: string): Promise<boolean> {
  const r = row(page, personId);
  const done = async () => (await r.count()) === 0 || await shown(r.getByTestId('person-retired')) === 'yes';
  const already = await done();
  await r.getByTestId('person-retire-date').fill(date);
  await r.getByTestId('person-retire').click();
  return until(page, done, r.getByTestId('person-error'), 'retire', already);
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
  await setField(r, 'person-hold-from', f.from);
  await setField(r, 'person-hold-to', f.to);
  await r.getByTestId('person-hold-toggle').click();
  return until(page, async () => dated
    ? (await lines.count()) > wasLines
    : await shown(r.getByTestId('person-hold')) !== wasText,
    r.getByTestId('person-error'), 'hold');
}

/** Start (true) or stop (false) watching the person as the signed-in user. */
export async function watch(page: Page, personId: string, on: boolean): Promise<boolean> {
  const r = row(page, personId);
  const done = async () => (await shown(r.getByTestId('person-watched'))) === (on ? 'yes' : 'no');
  const already = await done();
  await r.getByTestId('person-watch').click();
  return until(page, done, r.getByTestId('person-error'), 'watch', already);
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

/** Add an alias to the person: true once the row's aliases cell changed. */
export async function addAlias(page: Page, personId: string, alias: string): Promise<boolean> {
  const r = row(page, personId);
  const was = await shown(r.getByTestId('person-aliases'));
  await r.getByTestId('person-alias-input').fill(alias);
  await r.getByTestId('person-alias-add').click();
  return until(page, async () => (await shown(r.getByTestId('person-aliases'))) !== was,
    r.getByTestId('person-error'), 'add alias');
}

/** Merge the person into another (chosen by name): true once the merged person's row is gone. */
export async function merge(page: Page, personId: string, intoName: string): Promise<boolean> {
  const r = row(page, personId);
  try {
    await r.getByTestId('person-merge-into').selectOption({ label: intoName }, { timeout: 2000 });
  } catch {
    return false;
  }
  await r.getByTestId('person-merge').click();
  return until(page, async () => (await r.count()) === 0, r.getByTestId('person-error'), 'merge');
}

/** Transfer the person to another sheet by id: true once the row is gone from this sheet. */
export async function transfer(page: Page, personId: string, toSheet: string): Promise<boolean> {
  const r = row(page, personId);
  await r.getByTestId('person-transfer-to').fill(toSheet);
  await r.getByTestId('person-transfer').click();
  return until(page, async () => (await r.count()) === 0, r.getByTestId('person-error'), 'transfer');
}

/** Clone the sheet to another id: true once the screen shows that id as the last clone made. */
export async function cloneSheet(page: Page, toSheet: string): Promise<boolean> {
  const done = async () => (await shown(page.getByTestId('sheet-cloned-to'))) === toSheet;
  const already = await done();
  await page.getByTestId('sheet-clone-to').fill(toSheet);
  await page.getByTestId('sheet-clone').click();
  return until(page, done, page.getByTestId('sheet-error'), 'clone', already);
}

/** The ids of the person rows in screen order. */
export async function personOrder(page: Page): Promise<string[]> {
  return ids(rows(page), 'person-row-');
}

/** Move the person up or down the list: true once their place in the list changed. */
export async function movePerson(page: Page, personId: string, direction: string): Promise<boolean> {
  const was = (await personOrder(page)).indexOf(personId);
  const r = row(page, personId);
  await r.getByTestId(`person-move-${direction}`).click();
  return until(page, async () => (await personOrder(page)).indexOf(personId) !== was,
    r.getByTestId('person-error'), 'move person');
}

export async function sortBy(page: Page, key: string): Promise<void> {
  await page.getByTestId('sort-by').selectOption(key);
  await page.waitForTimeout(200);
}

/** Unretire the person: true once the row shows retired no. */
export async function unretire(page: Page, personId: string): Promise<boolean> {
  const r = row(page, personId);
  const done = async () => (await shown(r.getByTestId('person-retired'))) === 'no';
  const already = await done();
  await r.getByTestId('person-unretire').click();
  return until(page, done, r.getByTestId('person-error'), 'unretire', already);
}

/** Set the grade from the row's own select: true once the row shows it. */
export async function inlineGrade(page: Page, personId: string, grade: string): Promise<boolean> {
  const r = row(page, personId);
  const done = async () => (await shown(r.getByTestId('person-grade'))) === grade;
  const already = await done();
  try {
    await r.getByTestId('person-inline-grade').selectOption(grade, { timeout: 2000 });
  } catch {
    return false;
  }
  await r.getByTestId('person-inline-grade-save').click();
  return until(page, done, r.getByTestId('person-error'), 'inline grade', already);
}

/** Retire the selected people together from a date: how many were retired. */
export async function bulkRetire(page: Page, personIds: string[], date: string): Promise<number | null> {
  for (const id of personIds) await row(page, id).getByTestId('person-select').setChecked(true);
  await page.getByTestId('bulk-retire-date').fill(date);
  const was = await shown(page.getByTestId('bulk-retired'));
  await page.getByTestId('bulk-retire').click();
  const n = await countAfter(page, page.getByTestId('bulk-retired'), page.getByTestId('person-error'), 'bulk retire', was);
  return n === null ? null : Number(n);
}

type PlanFields = { amount: string; start: string; every?: string; day?: string; end: string; sheet?: string };

/** Add a plan on the person's row: the new plan row's id, or null when refused. */
export async function addPlan(page: Page, personId: string, f: PlanFields): Promise<string | null> {
  const r = row(page, personId);
  const plans = r.locator('[data-testid^="plan-row-"]');
  const before = await ids(plans, 'plan-row-');
  await r.getByTestId('plan-form-amount').fill(f.amount);
  await r.getByTestId('plan-form-start').fill(f.start);
  await setField(r, 'plan-form-every', f.every);
  await setField(r, 'plan-form-day', f.day);
  await setField(r, 'plan-form-sheet', f.sheet);
  await r.getByTestId('plan-form-end').fill(f.end);
  await r.getByTestId('plan-form-submit').click();
  let added: string | null = null;
  const ok = await until(page, async () => {
    const now = (await freshRows(plans, 'plan-row-', before, []))[0];
    added = now ? now.id : null;
    return added !== null;
  }, r.getByTestId('plan-error'), 'add plan');
  return ok ? added : null;
}

/** Pause (true) or resume (false) a plan: true once the plan shows it. */
export async function planPause(page: Page, planId: string, on: boolean): Promise<boolean> {
  const r = page.getByTestId(`plan-row-${planId}`);
  const done = async () => (await shown(r.getByTestId('plan-paused'))) === (on ? 'yes' : 'no');
  const already = await done();
  await r.getByTestId(on ? 'plan-pause' : 'plan-resume').click();
  return until(page, done, r.getByTestId('plan-error'), 'plan pause', already);
}

/** Change a plan's amount: true once the plan shows one more change line. */
export async function planAmount(page: Page, planId: string, amount: string): Promise<boolean> {
  const r = page.getByTestId(`plan-row-${planId}`);
  const lines = r.getByTestId('plan-change-line');
  const was = await lines.count();
  await r.getByTestId('plan-amount').fill(amount);
  await r.getByTestId('plan-amount-save').click();
  return until(page, async () => (await lines.count()) > was, r.getByTestId('plan-error'), 'plan amount');
}

/** Generate the plans' claims: how many were made, or null when refused. */
export async function generatePlans(page: Page): Promise<number | null> {
  const cell = page.getByTestId('plan-generated-count');
  const was = await shown(cell);
  await page.getByTestId('plan-generate').click();
  const n = await countAfter(page, cell, page.getByTestId('plan-generate-error'), 'generate plans', was);
  return n === null ? null : Number(n);
}

/** Flag the person with a reason until a date: true once the row shows `shows` (the reason while
 *  the flag is in force; nothing when its until-date is already past, in which case the step
 *  counts as taken once no error has shown for a while). */
export async function flag(page: Page, personId: string, reason: string, until_: string, shows: string = reason): Promise<boolean> {
  const r = row(page, personId);
  const done = async () => (await shown(r.getByTestId('person-flag-shown'))) === shows.trim();
  const already = shows.trim() === '' || await done();
  await r.getByTestId('person-flag-reason').fill(reason);
  await r.getByTestId('person-flag-until').fill(until_);
  await r.getByTestId('person-flag').click();
  return until(page, done, r.getByTestId('person-error'), 'flag', already);
}

/** Set the person's approver: true once the row shows the user. */
export async function setApprover(page: Page, personId: string, user: string): Promise<boolean> {
  const r = row(page, personId);
  const done = async () => (await shown(r.getByTestId('person-approver'))) === user.trim();
  const already = await done();
  await r.getByTestId('person-approver-input').fill(user);
  await r.getByTestId('person-approver-save').click();
  return until(page, done, r.getByTestId('person-error'), 'set approver', already);
}

/** Undo the transfer that brought the person here: true once the row is gone. */
export async function transferUndo(page: Page, personId: string): Promise<boolean> {
  const r = row(page, personId);
  await r.getByTestId('person-transfer-undo').click();
  return until(page, async () => (await r.count()) === 0, r.getByTestId('person-error'), 'transfer undo');
}

export async function exportStatement(page: Page, personId: string): Promise<string> {
  const [download] = await Promise.all([page.waitForEvent('download'), row(page, personId).getByTestId('person-export-statement').click()]);
  const path = await download.path();
  if (!path) throw new Error('export produced no file');
  return readFileSync(path, 'utf8');
}

/** Undo the last merge into this person: true once the merged person's row is back (one more row). */
export async function mergeUndo(page: Page, personId: string): Promise<boolean> {
  const r = row(page, personId);
  const was = await rows(page).count();
  await r.getByTestId('person-merge-undo').click();
  return until(page, async () => (await rows(page).count()) > was, r.getByTestId('person-error'), 'merge undo');
}

/** Hold the selected people together: how many were held. */
export async function bulkHold(page: Page, personIds: string[], f: { from?: string; to?: string }): Promise<number | null> {
  for (const id of personIds) await row(page, id).getByTestId('person-select').setChecked(true);
  await setField(page, 'bulk-hold-from', f.from);
  await setField(page, 'bulk-hold-to', f.to);
  const was = await shown(page.getByTestId('bulk-held'));
  await page.getByTestId('bulk-hold').click();
  const n = await countAfter(page, page.getByTestId('bulk-held'), page.getByTestId('person-error'), 'bulk hold', was);
  return n === null ? null : Number(n);
}

export async function exportPerson(page: Page, personId: string): Promise<string> {
  const [download] = await Promise.all([page.waitForEvent('download'), row(page, personId).getByTestId('person-export').click()]);
  const path = await download.path();
  if (!path) throw new Error('export produced no file');
  return readFileSync(path, 'utf8');
}

/** Give the selected people a grade together: how many were changed. */
export async function bulkGrade(page: Page, personIds: string[], f: { grade: string; from?: string }): Promise<number | null> {
  for (const id of personIds) await row(page, id).getByTestId('person-select').setChecked(true);
  await page.getByTestId('bulk-grade').selectOption(f.grade);
  await setField(page, 'bulk-grade-from', f.from);
  const was = await shown(page.getByTestId('bulk-graded'));
  await page.getByTestId('bulk-grade-apply').click();
  const n = await countAfter(page, page.getByTestId('bulk-graded'), page.getByTestId('person-error'), 'bulk grade', was);
  return n === null ? null : Number(n);
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

type ClaimFields = { amount?: string; date?: string; note?: string; corrects?: string; ref?: string; tags?: string };

/** Fill the claim form. `clear` (an add) also empties every field the test does not give (a
 *  blank date takes the default), so nothing typed for a refused add carries into this one; an
 *  edit leaves the fields it is not given as the editor filled them. */
async function fillClaimForm(r: Locator, f: ClaimFields, clear = false) {
  for (const key of ['amount', 'date', 'note', 'corrects', 'ref', 'tags'] as const) {
    const v = f[key];
    if (v !== undefined) await r.getByTestId(`claim-form-${key}`).fill(v);
    else if (clear) await setField(r, `claim-form-${key}`, undefined);
  }
}

/** Add a claim from the person's row. The new claim's id, or null when refused. */
export async function addClaim(page: Page, personId: string, f: ClaimFields & { amount: string }): Promise<string | null> {
  const r = row(page, personId);
  const before = await ids(claimRows(r), 'claim-row-');
  await fillClaimForm(r, f, true);
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
  await fillClaimForm(r, f, true);
  await r.getByTestId('claim-form-submit').dblclick();
  const ok = await until(page, async () => (await addedClaim(r, before, f)) !== null,
    r.getByTestId('claim-form-error'), 'add claim twice');
  await page.waitForTimeout(500);          // let a second record land, if the app stores one
  return ok ? addedClaim(r, before, f) : null;
}

/** Add a claim from a template: the new claim's id, or null when refused. */
export async function claimFromTemplate(page: Page, personId: string, template: string): Promise<string | null> {
  const r = row(page, personId);
  const before = await ids(claimRows(r), 'claim-row-');
  try {
    await r.getByTestId('claim-template-select').selectOption({ label: template }, { timeout: 2000 });
  } catch {
    return null;
  }
  await r.getByTestId('claim-template-add').click();
  let added: string | null = null;
  const ok = await until(page, async () => (added = await addedAnyClaim(r, before)) !== null,
    r.getByTestId('claim-form-error'), 'claim from template');
  return ok ? added : null;
}

/** Add a line to a draft claim: true once the claim shows one more line. */
export async function addLine(page: Page, claimId: string, f: { label: string; qty: string; unit: string }): Promise<boolean> {
  const c = claimRow(page, claimId);
  const lines = c.getByTestId('claim-lines-line');
  const was = await lines.count();
  await c.getByTestId('claim-line-label').fill(f.label);
  await c.getByTestId('claim-line-qty').fill(f.qty);
  await c.getByTestId('claim-line-unit').fill(f.unit);
  await c.getByTestId('claim-line-add').click();
  return until(page, async () => (await lines.count()) > was, c.getByTestId('claim-error'), 'add line');
}

/** Re-date the selected claims together: how many were re-dated and how many refused. */
export async function bulkRedate(page: Page, claimIds: string[], date: string): Promise<{ redated: number; refused: number }> {
  for (const id of claimIds) await claimRow(page, id).getByTestId('claim-select').setChecked(true);
  await page.getByTestId('bulk-redate-date').fill(date);
  await page.getByTestId('bulk-redate').click();
  await expect(page.getByTestId('bulk-redated')).toBeVisible({ timeout: WAIT });
  return {
    redated: Number(await shown(page.getByTestId('bulk-redated'))),
    refused: Number(await shown(page.getByTestId('bulk-redate-refused'))),
  };
}

/** Paste lines of claims into the paste box: how many were added, or null when refused. */
export async function pasteClaims(page: Page, text: string): Promise<number | null> {
  // A paste that only updates DRAFTs in place adds no row: the count it shows is the
  // result, judged against the value before the click as countAfter judges it.
  const cell = page.getByTestId('paste-added');
  const before = await claimRows(page).count();
  const was = (await cell.count()) > 0 ? await shown(cell) : '';
  await page.getByTestId('paste-claims').fill(text);
  await page.getByTestId('paste-submit').click();
  let cleared = false;
  const counted = async () => {
    if ((await cell.count()) === 0) return false;
    const now = await shown(cell);
    if (now === '') { cleared = true; return false; }
    return now !== was || was === '' || cleared;
  };
  const ok = await until(page, async () => (await counted()) || (await claimRows(page).count()) > before,
    page.getByTestId('paste-error'), 'paste claims');
  return ok ? Number(await shown(cell)) : null;
}

/** Edit a claim through its edit button and the claim form; true when the row shows the change. */
export async function editClaim(page: Page, claimId: string, f: ClaimFields): Promise<boolean> {
  const c = claimRow(page, claimId);
  const r = personRowOf(page, claimId);
  await c.getByTestId('claim-edit').click();
  if (await c.getByTestId('claim-error').isVisible().catch(() => false)) return false;
  const ok = await saveClaimEditor(page, c, r, f, 'edit claim');
  if (!ok) await cancelOpenEditor(c);
  return ok;
}

/** Submit the open claim editor with `f` and judge the outcome. When the typed values are what the
 *  row already shows, the save cannot be seen on the row: the step then counts as saved only once
 *  no error has shown for a while (the `already` rule of until()). */
async function saveClaimEditor(page: Page, c: Locator, r: Locator, f: ClaimFields, what: string): Promise<boolean> {
  await fillClaimForm(r, f);
  const error = c.getByTestId('claim-error').or(r.getByTestId('claim-form-error'));
  const was = await editedCells(c, f);
  const already = await editedShows(c, f, was);
  await r.getByTestId('claim-form-submit').click();
  return until(page, async () => editedShows(c, f, was), error, what, already);
}

/** A refused save leaves the editor open (the contract says a refusal changes nothing): close it
 *  with the cancel button when the screen has one, so the shared form is back to adding. */
async function cancelOpenEditor(c: Locator): Promise<void> {
  const cancel = c.getByTestId('claim-cancel');
  if ((await cancel.count()) > 0 && await cancel.isVisible().catch(() => false)) await cancel.click().catch(() => undefined);
}

/** The cells an edit will be judged by, as they are before the save. */
async function editedCells(c: Locator, f: ClaimFields): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  for (const k of Object.keys(f)) out[k] = await shown(c.getByTestId(`claim-${k}`));
  return out;
}

/** Whether the row shows the edit: an amount, date, note or corrects cell equal to what was
 *  typed; a ref or tags cell, which the app normalises, is taken as saved once the form's error
 *  is not showing and the cell is no longer what it was (or the typed value, normalised, is a
 *  value it could not have had before). */
async function editedShows(c: Locator, f: ClaimFields, was: Record<string, string>): Promise<boolean> {
  for (const [k, v] of Object.entries(f)) {
    const cell = await shown(c.getByTestId(`claim-${k}`));
    if (k === 'ref' || k === 'tags') {
      const norm = k === 'ref' ? v.trim() : v.split(',').map((t) => t.trim().toLowerCase()).filter((t) => t)
        .filter((t, i, a) => a.indexOf(t) === i).sort().join(', ');
      if (cell !== norm) return false;
    } else if (cell !== v) return false;
  }
  return true;
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
  return saveClaimEditor(page, c, r, f, 'save claim editor');
}

/** Change a draft's amount in the row (its own entry and save button): true when the row shows it. */
export async function editInline(page: Page, claimId: string, amount: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const done = async () => (await shown(c.getByTestId('claim-amount'))) === money(amount);
  const already = await done();
  await c.getByTestId('claim-inline-amount').fill(amount);
  await c.getByTestId('claim-inline-save').click();
  return until(page, done, c.getByTestId('claim-error'), 'inline amount', already);
}

/** Remind a claim: true once the row's reminded date moved to the sheet's today. */
export async function remind(page: Page, claimId: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const today = await shown(page.getByTestId('sheet-today'));
  const done = async () => (await shown(c.getByTestId('claim-reminded'))) === today;
  const already = await done();
  await c.getByTestId('claim-remind').click();
  return until(page, done, c.getByTestId('claim-error'), 'remind', already);
}

/** Add a comment to a claim: true once the claim shows one more comment line. */
export async function addComment(page: Page, claimId: string, text: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const lines = c.getByTestId('claim-comments-line');
  const was = await lines.count();
  await c.getByTestId('claim-comment-text').fill(text);
  await c.getByTestId('claim-comment-add').click();
  return until(page, async () => (await lines.count()) > was, c.getByTestId('claim-error'), 'comment');
}

/** Add a holiday to the sheet: true once its row shows. */
export async function addHoliday(page: Page, date: string): Promise<boolean> {
  const r = page.getByTestId(`holiday-row-${date}`);
  const already = (await r.count()) > 0;
  await page.getByTestId('holiday-date').fill(date);
  await page.getByTestId('holiday-add').click();
  return until(page, async () => (await r.count()) > 0, page.getByTestId('holiday-error'), 'add holiday', already);
}

/** Add a void reason to the sheet's list: true once its row shows. */
export async function addVoidReason(page: Page, reason: string): Promise<boolean> {
  const r = page.getByTestId(`void-reason-row-${reason.trim()}`);
  const already = reason.trim() !== '' && (await r.count()) > 0;
  await page.getByTestId('void-reason-text').fill(reason);
  await page.getByTestId('void-reason-add').click();
  return until(page, async () => reason.trim() !== '' && (await r.count()) > 0,
    page.getByTestId('void-reason-error'), 'add void reason', already);
}

/** Preview a candidate rate: the count of claims that would change and the total difference, or
 *  null when the screen refused (no preview, a bad rate). */
export async function ratePreview(page: Page, grade: string, pct: string): Promise<{ count: number; diff: string } | null> {
  await page.getByTestId('rate-preview-grade').selectOption(grade);
  await page.getByTestId('rate-preview-pct').fill(pct);
  const countCell = page.getByTestId('rate-preview-count');
  const diffCell = page.getByTestId('rate-preview-diff');
  const wasCount = await shown(countCell), wasDiff = await shown(diffCell);
  await page.getByTestId('rate-preview').click();
  await page.waitForTimeout(300);
  const ok = await until(page, async () => (await countCell.count()) > 0 && (await shown(countCell)) !== ''
    && ((await shown(countCell)) !== wasCount || (await shown(diffCell)) !== wasDiff || wasCount === ''),
    page.getByTestId('rate-preview-error'), 'rate preview');
  return ok ? { count: Number(await shown(countCell)), diff: await shown(diffCell) } : null;
}

export async function claimEditCancel(page: Page, claimId: string): Promise<void> {
  await cancelOpenEditor(claimRow(page, claimId));
}

/** open | settle | void | approve | reject(reason) | void(reason): true when the status changed.
 *  A reject's reason is typed; a void's is chosen from the sheet's list. */
export async function claimAction(page: Page, claimId: string, action: string, reason?: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const was = await shown(c.getByTestId('claim-status'));
  if (reason !== undefined && action === 'void') {
    try {
      await c.getByTestId('claim-void-reason').selectOption({ label: reason }, { timeout: 2000 });
    } catch {
      return false;             // not offered: the screen refuses it by leaving it out
    }
  } else if (reason !== undefined && action === 'settle') {
    await c.getByTestId('claim-settle-date').fill(reason);
  } else if (reason !== undefined) await c.getByTestId('claim-reject-reason').fill(reason);
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
  const done = async () => (await shown(c.getByTestId('claim-deferred-until'))) === until_;
  const already = await done();
  await c.getByTestId('claim-defer-date').fill(until_);
  await c.getByTestId('claim-defer').click();
  return until(page, done, c.getByTestId('claim-error'), 'defer', already);
}

/** Raise the claim's proofs count by one (of a kind, when the claim offers kinds): true once the count moved. */
export async function addProof(page: Page, claimId: string, kind?: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const was = await shown(c.getByTestId('claim-proofs'));
  if (kind !== undefined) {
    try {
      await c.getByTestId('claim-proof-kind').selectOption(kind, { timeout: 2000 });
    } catch {
      return false;
    }
  }
  await c.getByTestId('claim-proof').click();
  return until(page, async () => (await shown(c.getByTestId('claim-proofs'))) !== was,
    c.getByTestId('claim-error'), 'add proof');
}

/** Waive the claim's late charge: true once the row shows waived yes. */
export async function waive(page: Page, claimId: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const done = async () => (await shown(c.getByTestId('claim-waived'))) === 'yes';
  const already = await done();
  await c.getByTestId('claim-waive').click();
  return until(page, done, c.getByTestId('claim-error'), 'waive', already);
}

/** Hold or release the claim: true once its held flag flipped. */
export async function holdClaim(page: Page, claimId: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const was = await shown(c.getByTestId('claim-held'));
  await c.getByTestId('claim-hold').click();
  return until(page, async () => (await shown(c.getByTestId('claim-held'))) !== was, c.getByTestId('claim-error'), 'hold claim');
}

/** Settle part of an open claim: the new (settled) claim's id, or null when refused. */
export async function settlePart(page: Page, personId: string, claimId: string, amount: string): Promise<string | null> {
  const r = row(page, personId);
  const c = claimRow(page, claimId);
  const before = await ids(claimRows(r), 'claim-row-');
  await c.getByTestId('claim-settle-part-amount').fill(amount);
  await c.getByTestId('claim-settle-part').click();
  let added: string | null = null;
  const ok = await until(page, async () => (added = await addedClaim(r, before, { amount })) !== null,
    c.getByTestId('claim-error'), 'settle part');
  return ok ? added : null;
}

/** Share the claim with a person (by name) at a share percent: true once the claim's shares cell changed. */
export async function shareClaim(page: Page, claimId: string, who: string, share: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const was = await shown(c.getByTestId('claim-shares'));
  try {
    await c.getByTestId('claim-share-select').selectOption({ label: who }, { timeout: 2000 });
  } catch {
    return false;
  }
  await c.getByTestId('claim-share-pct').fill(share);
  await c.getByTestId('claim-share-add').click();
  return until(page, async () => (await shown(c.getByTestId('claim-shares'))) !== was, c.getByTestId('claim-error'), 'share claim');
}

/** Assign a review to a user: true once the claim shows the user. */
export async function assignReview(page: Page, claimId: string, user: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const done = async () => user.trim() !== '' && (await shown(c.getByTestId('claim-review-assigned'))) === user.trim();
  const already = await done();
  await c.getByTestId('claim-review-user').fill(user);
  await c.getByTestId('claim-review-assign').click();
  return until(page, done, c.getByTestId('claim-error'), 'assign review', already);
}

/** Set the claim's due-by directly: true once the row shows it. */
export async function setDueBy(page: Page, claimId: string, date: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const done = async () => (await shown(c.getByTestId('claim-due-by'))) === date;
  const already = await done();
  await c.getByTestId('claim-due-by-new').fill(date);
  await c.getByTestId('claim-set-due-by').click();
  return until(page, done, c.getByTestId('claim-error'), 'set due-by', already);
}

/** Re-date a claim: true once the row shows the new date. */
export async function redate(page: Page, claimId: string, date: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const done = async () => (await shown(c.getByTestId('claim-date'))) === date;
  const already = await done();
  await c.getByTestId('claim-date-new').fill(date);
  await c.getByTestId('claim-redate').click();
  return until(page, done, c.getByTestId('claim-error'), 'redate', already);
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
  const done = async () => (await rowsNamed(page, toName).filter({ has: claimRow(page, claimId) }).count()) > 0;
  const already = await done();
  await c.getByTestId('claim-reassign-to').selectOption({ label: toName });
  await c.getByTestId('claim-reassign').click();
  return until(page, done, c.getByTestId('claim-error'), 'reassign', already);
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

export async function bulkReassign(page: Page, claimIds: string[], toName: string): Promise<{ reassigned: number; refused: number }> {
  for (const id of claimIds) await claimRow(page, id).getByTestId('claim-select').setChecked(true);
  await page.getByTestId('bulk-reassign-to').selectOption({ label: toName });
  await page.getByTestId('bulk-reassign').click();
  await expect(page.getByTestId('bulk-reassigned')).toBeVisible({ timeout: WAIT });
  return {
    reassigned: Number(await shown(page.getByTestId('bulk-reassigned'))),
    refused: Number(await shown(page.getByTestId('bulk-reassign-refused'))),
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
/** Quick-add a claim by typed name: the new claim's id, or null when refused. `personId` names
 *  the row the claim lands on when the typed text is not the name that row shows, an alias;
 *  without it the row is found by the typed name. */
export async function quickAdd(page: Page, f: { name: string; amount: string }, personId?: string): Promise<string | null> {
  const before = await ids(claimRows(page), 'claim-row-');
  await page.getByTestId('quick-name').fill(f.name);
  await page.getByTestId('quick-amount').fill(f.amount);
  await page.getByTestId('quick-submit').click();
  const scope = personId !== undefined ? row(page, personId) : rowsNamed(page, f.name);
  let added: string | null = null;
  const ok = await until(page, async () => (added = await addedClaim(scope, before, { amount: f.amount })) !== null,
    page.getByTestId('quick-error'), 'quick add');
  return ok ? added : null;
}

// ---- rates, periods, settings, roles, export -------------------------------------------------

export async function setRate(page: Page, f: { grade: string; pct: string; from?: string; to?: string }): Promise<boolean> {
  await page.getByTestId('rate-form-grade').selectOption(f.grade);
  await page.getByTestId('rate-form-pct').fill(f.pct);
  await setField(page, 'rate-form-from', f.from);
  await setField(page, 'rate-form-to', f.to);
  const r = page.getByTestId(`rate-row-${f.grade}-${f.from ?? 'start'}`);
  const done = async () => (await r.count()) > 0 && await shown(r.getByTestId('rate-pct')) === f.pct
    && (f.to === undefined || await shown(r.getByTestId('rate-to')) === f.to);
  const already = await done();
  await page.getByTestId('rate-form-submit').click();
  return until(page, done, page.getByTestId('rate-form-error'), 'set rate', already);
}

export async function addPeriod(page: Page, f: { name: string; from: string; to: string; cap?: string }): Promise<boolean> {
  const all = page.locator('[data-testid^="period-row-"]');
  const before = await all.count();
  await page.getByTestId('period-form-name').fill(f.name);
  await page.getByTestId('period-form-from').fill(f.from);
  await page.getByTestId('period-form-to').fill(f.to);
  await setField(page, 'period-form-cap', f.cap);
  await page.getByTestId('period-form-submit').click();
  return until(page, async () => (await all.count()) > before, page.getByTestId('period-form-error'), 'add period');
}

/** Generate monthly periods between two dates: how many were made, or null when refused. */
export async function generatePeriods(page: Page, from: string, to: string): Promise<number | null> {
  await page.getByTestId('period-generate-from').fill(from);
  await page.getByTestId('period-generate-to').fill(to);
  const was = await shown(page.getByTestId('period-generated'));
  await page.getByTestId('period-generate').click();
  const n = await countAfter(page, page.getByTestId('period-generated'), page.getByTestId('period-generate-error'), 'generate periods', was);
  return n === null ? null : Number(n);
}

/** close | soft-close | reopen | lock | unlock: true when the period's closed (or locked) flag changed. */
export async function periodAction(page: Page, name: string, action: string): Promise<boolean> {
  const r = page.getByTestId(`period-row-${name}`);
  const cell = action === 'lock' || action === 'unlock' ? 'period-locked' : 'period-closed';
  const was = await shown(r.getByTestId(cell));
  await r.getByTestId(`period-${action}`).click();
  return until(page, async () => await shown(r.getByTestId(cell)) !== was,
    r.getByTestId('period-error'), `period ${action}`);
}

export async function setSetting(page: Page, key: string, value: string): Promise<void> {
  await page.getByTestId(`setting-${key}`).fill(value);
  await setField(page, `setting-${key}-from`, undefined);    // undated: from the sheet's today
  await page.getByTestId('settings-save').click();
  await expect(page.getByTestId(`setting-${key}-current`)).toHaveText(value, { timeout: WAIT });
}

/** Save a setting where the screen may refuse it: true when the value in force shows it. */
export async function setSettingTry(page: Page, key: string, value: string): Promise<boolean> {
  const done = async () => (await shown(page.getByTestId(`setting-${key}-current`))) === value;
  const already = await done();
  await page.getByTestId(`setting-${key}`).fill(value);
  await setField(page, `setting-${key}-from`, undefined);
  await page.getByTestId('settings-save').click();
  return until(page, done, page.getByTestId('settings-error'), 'save setting', already);
}

/** Save a setting from a date; `shows` is the value in force on the sheet's today once saved
 *  (a from-date after today leaves the current value as it was). */
export async function setSettingDated(page: Page, key: string, value: string, from: string, shows: string): Promise<boolean> {
  const done = async () => (await shown(page.getByTestId(`setting-${key}-current`))) === shows
    && !(await page.getByTestId('settings-error').isVisible().catch(() => false));
  const already = await done();
  await page.getByTestId(`setting-${key}`).fill(value);
  await page.getByTestId(`setting-${key}-from`).fill(from);
  await page.getByTestId('settings-save').click();
  const ok = await until(page, done, page.getByTestId('settings-error'), 'save dated setting', already);
  if (ok) await page.waitForTimeout(300);     // the value may already have shown; let the save land
  return ok;
}

/** Save a claim template: true once its row shows the amount and note. */
export async function saveTemplate(page: Page, f: { name: string; amount: string; note: string }): Promise<boolean> {
  const r = page.getByTestId(`template-row-${f.name.trim()}`);
  const done = async () => f.name.trim() !== '' && (await r.count()) > 0
    && (await shown(r.getByTestId('template-amount'))) === money(f.amount) && (await shown(r.getByTestId('template-note'))) === f.note;
  const already = await done();
  await page.getByTestId('template-form-name').fill(f.name);
  await page.getByTestId('template-form-amount').fill(f.amount);
  await page.getByTestId('template-form-note').fill(f.note);
  await page.getByTestId('template-form-submit').click();
  return until(page, done, page.getByTestId('template-form-error'), 'save template', already);
}

/** Add a pool: true once its row shows. */
export async function addPool(page: Page, name: string): Promise<boolean> {
  const r = page.getByTestId(`pool-row-${name.trim()}`);
  const already = name.trim() !== '' && (await r.count()) > 0;
  await page.getByTestId('pool-form-name').fill(name);
  await page.getByTestId('pool-form-submit').click();
  return until(page, async () => name.trim() !== '' && (await r.count()) > 0, page.getByTestId('pool-form-error'), 'add pool', already);
}

/** Add a member (a person's name, or pool:<name>) to a pool: true once the pool shows one more member. */
export async function addPoolMember(page: Page, pool: string, f: { member: string; share: string; from?: string; to?: string }): Promise<boolean> {
  const r = page.getByTestId(`pool-row-${pool}`);
  const members = page.locator(`[data-testid^="pool-member-${pool}-"]`);
  const was = await members.count();
  try {
    await r.getByTestId('pool-member-select').selectOption({ label: f.member }, { timeout: 2000 });
  } catch {
    return false;
  }
  await r.getByTestId('pool-member-share').fill(f.share);
  await setField(r, 'pool-member-from', f.from);
  await setField(r, 'pool-member-to', f.to);
  await r.getByTestId('pool-member-add').click();
  return until(page, async () => (await members.count()) > was, r.getByTestId('pool-error'), 'add pool member');
}

/** Paste people into the import box: {added, updated?}, or null when refused. */
export async function importPeople(page: Page, text: string): Promise<{ added: number; updated?: number } | null> {
  const cell = page.getByTestId('import-added');
  const was = await shown(cell);
  await page.getByTestId('import-people').fill(text);
  await page.getByTestId('import-submit').click();
  const n = await countAfter(page, cell, page.getByTestId('import-error'), 'import people', was);
  if (n === null) return null;
  const out: { added: number; updated?: number } = { added: Number(n) };
  const upd = page.getByTestId('import-updated');
  if ((await upd.count()) > 0) out.updated = Number(await shown(upd));
  return out;
}

/** Paste rates into the rate import box: how many were set, or null when refused. */
export async function importRates(page: Page, text: string): Promise<number | null> {
  const cell = page.getByTestId('import-rates-set');
  const was = await shown(cell);
  await page.getByTestId('import-rates').fill(text);
  await page.getByTestId('import-rates-submit').click();
  const n = await countAfter(page, cell, page.getByTestId('import-rates-error'), 'import rates', was);
  return n === null ? null : Number(n);
}

/** Generate a fiscal year's monthly periods: how many were made, or null when refused. */
export async function generateFy(page: Page, year: string): Promise<number | null> {
  await page.getByTestId('period-generate-fy-year').fill(year);
  const was = await shown(page.getByTestId('period-generated'));
  await page.getByTestId('period-generate-fy').click();
  const n = await countAfter(page, page.getByTestId('period-generated'), page.getByTestId('period-generate-error'), 'generate fiscal year', was);
  return n === null ? null : Number(n);
}

/** Search the tree: the result lines in order, once the screen says how many there are. */
export async function search(page: Page, text: string): Promise<string[]> {
  await page.getByTestId('search-name').fill(text);
  await page.getByTestId('search').click();
  const count = page.getByTestId('search-count');
  await expect.poll(async () => {
    const n = await shown(count);
    return n !== '' && Number(n) === (await page.getByTestId('search-line').count());
  }, { timeout: WAIT }).toBe(true);
  return (await page.getByTestId('search-line').allTextContents()).map((t) => t.trim());
}

export async function exportClaims(page: Page): Promise<string> {
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('export-claims').click()]);
  const path = await download.path();
  if (!path) throw new Error('export produced no file');
  return readFileSync(path, 'utf8');
}

export async function exportPools(page: Page): Promise<string> {
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('export-pools').click()]);
  const path = await download.path();
  if (!path) throw new Error('export produced no file');
  return readFileSync(path, 'utf8');
}

/** The audit viewer, filtered: its lines are exactly `expected`, in order, each matched by its
 *  start (a line may carry further fields after the expected ones, as the file's lines may). */
export async function expectAuditView(page: Page, filter: string, expected: string[]): Promise<void> {
  await page.getByTestId('audit-filter').fill(filter);
  await page.getByTestId('audit-show').click();
  const lines = page.getByTestId('audit-line');
  await expect.poll(() => lines.count(), { timeout: WAIT }).toBe(expected.length);
  const texts = (await lines.allTextContents()).map((t) => t.trim());
  texts.forEach((line, i) => {
    const e = expected[i];
    const ok = line === e || line.startsWith(e + ' ');
    expect(ok, `audit view line ${i + 1}\n  expected: ${e}\n  actual:   ${line}`).toBe(true);
  });
}

/** Add a sheet note: true once one more note line shows. */
export async function addNote(page: Page, text: string): Promise<boolean> {
  const lines = page.getByTestId('note-line');
  const was = await lines.count();
  await page.getByTestId('note-text').fill(text);
  await page.getByTestId('note-add').click();
  return until(page, async () => (await lines.count()) > was, page.getByTestId('note-error'), 'add note');
}

/** Add a crew with a lead (by name): true once its row shows. */
export async function addCrew(page: Page, name: string, lead: string): Promise<boolean> {
  const r = page.getByTestId(`crew-row-${name.trim()}`);
  const already = name.trim() !== '' && (await r.count()) > 0;
  await page.getByTestId('crew-form-name').fill(name);
  try {
    await page.getByTestId('crew-form-lead').selectOption({ label: lead }, { timeout: 2000 });
  } catch {
    return false;
  }
  await page.getByTestId('crew-form-submit').click();
  return until(page, async () => name.trim() !== '' && (await r.count()) > 0, page.getByTestId('crew-form-error'), 'add crew', already);
}

/** Add a member (by name) to a crew: true once the crew shows one more member line. */
export async function addCrewMember(page: Page, crew: string, who: string): Promise<boolean> {
  const r = page.getByTestId(`crew-row-${crew}`);
  const members = r.getByTestId('crew-member-line');
  const was = await members.count();
  try {
    await r.getByTestId('crew-member-select').selectOption({ label: who }, { timeout: 2000 });
  } catch {
    return false;
  }
  await r.getByTestId('crew-member-add').click();
  return until(page, async () => (await members.count()) > was, r.getByTestId('crew-error'), 'add crew member');
}

/** Generate a year's quarterly periods: how many were made, or null when refused. */
export async function generateQuarters(page: Page, year: string): Promise<number | null> {
  await page.getByTestId('period-generate-quarters-year').fill(year);
  const was = await shown(page.getByTestId('period-generated'));
  await page.getByTestId('period-generate-quarters').click();
  const n = await countAfter(page, page.getByTestId('period-generated'), page.getByTestId('period-generate-error'), 'generate quarters', was);
  return n === null ? null : Number(n);
}

/** Paste opening balances: how many lines went through, or null when refused. */
export async function importOpenings(page: Page, text: string): Promise<number | null> {
  const cell = page.getByTestId('opening-import-done');
  const was = await shown(cell);
  await page.getByTestId('opening-import').fill(text);
  await page.getByTestId('opening-import-submit').click();
  const n = await countAfter(page, cell, page.getByTestId('opening-error'), 'import openings', was);
  return n === null ? null : Number(n);
}

/** Void the selected claims together: how many were voided and how many refused. */
export async function bulkVoid(page: Page, claimIds: string[], reason?: string): Promise<{ voided: number; refused: number }> {
  for (const id of claimIds) await claimRow(page, id).getByTestId('claim-select').setChecked(true);
  if (reason !== undefined) await page.getByTestId('bulk-void-reason').selectOption({ label: reason });
  await page.getByTestId('bulk-void').click();
  await expect(page.getByTestId('bulk-voided')).toBeVisible({ timeout: WAIT });
  return {
    voided: Number(await shown(page.getByTestId('bulk-voided'))),
    refused: Number(await shown(page.getByTestId('bulk-void-refused'))),
  };
}

/** Choose the signed-in user's locale. */
export async function setLocale(page: Page, locale: string): Promise<void> {
  await page.getByTestId('user-locale').selectOption(locale);
  await page.getByTestId('user-locale-save').click();
  await expect(page.getByTestId('user-locale-current')).toHaveText(locale, { timeout: WAIT });
}

/** Hide exactly these columns of the people list (every other column shown). */
export async function setColumns(page: Page, off: string[]): Promise<void> {
  const boxes = page.locator('[data-testid^="column-"]');
  for (const h of await boxes.elementHandles()) {
    const id = idOf(await h.getAttribute('data-testid'), 'column-');
    const want = !off.includes(id);
    if ((await h.isChecked()) !== want) await h.click();
  }
  await page.waitForTimeout(200);
}

/** The activity lines, in order. */
export async function activity(page: Page): Promise<string[]> {
  await page.getByTestId('activity-show').click();
  await page.waitForTimeout(300);
  return (await page.getByTestId('activity-line').allTextContents()).map((t) => t.trim());
}

/** The duplicate-claim lines, in order. */
export async function dups(page: Page): Promise<string[]> {
  await page.getByTestId('dups-show').click();
  await page.waitForTimeout(300);
  return (await page.getByTestId('dup-line').allTextContents()).map((t) => t.trim());
}

/** The claim's timeline: its lines are exactly `expected`, in order, each matched by its start. */
export async function expectClaimTimeline(page: Page, claimId: string, expected: string[]): Promise<void> {
  const c = claimRow(page, claimId);
  await c.getByTestId('claim-timeline').click();
  const lines = c.getByTestId('claim-timeline-line');
  await expect.poll(() => lines.count(), { timeout: WAIT }).toBe(expected.length);
  const texts = (await lines.allTextContents()).map((t) => t.trim());
  texts.forEach((line, i) => {
    const e = expected[i];
    const ok = line === e || line.startsWith(e + ' ');
    expect(ok, `timeline line ${i + 1}\n  expected: ${e}\n  actual:   ${line}`).toBe(true);
  });
}

/** Compare two snapshot dates: the result lines in order. */
export async function snapshotCompare(page: Page, from: string, to: string): Promise<string[]> {
  await page.getByTestId('snapshot-from').fill(from);
  await page.getByTestId('snapshot-to').fill(to);
  await page.getByTestId('snapshot-compare').click();
  await page.waitForTimeout(300);
  return (await page.getByTestId('snapshot-line').allTextContents()).map((t) => t.trim());
}

/** Roll the year over: how many periods were made, or null when refused. */
export async function rollover(page: Page, year: string): Promise<number | null> {
  await page.getByTestId('rollover-year').fill(year);
  const was = await shown(page.getByTestId('rollover-made'));
  await page.getByTestId('rollover').click();
  const n = await countAfter(page, page.getByTestId('rollover-made'), page.getByTestId('rollover-error'), 'rollover', was);
  return n === null ? null : Number(n);
}

/** Undo the last rollover: true once the screen shows it undone. */
export async function rolloverUndo(page: Page): Promise<boolean> {
  const all = page.locator('[data-testid^="period-row-"]');
  const was = await all.count();
  await page.getByTestId('rollover-undo').click();
  return until(page, async () => (await all.count()) < was, page.getByTestId('rollover-error'), 'rollover undo');
}

/** Compare this sheet with another: the lines in order. */
export async function compareSheet(page: Page, other: string): Promise<string[]> {
  await page.getByTestId('compare-sheet').fill(other);
  await page.getByTestId('compare').click();
  await page.waitForTimeout(300);
  return (await page.getByTestId('compare-line').allTextContents()).map((t) => t.trim());
}

/** Retire a grade: true once the screen shows it retired. */
export async function retireGrade(page: Page, grade: string): Promise<boolean> {
  const r = page.getByTestId(`grade-row-${grade}`);
  const done = async () => (await r.count()) > 0 && (await shown(r.getByTestId('grade-retired'))) === 'yes';
  const already = await done();
  try {
    await page.getByTestId('grade-retire-select').selectOption(grade, { timeout: 2000 });
  } catch {
    return false;
  }
  await page.getByTestId('grade-retire').click();
  return until(page, done, page.getByTestId('grade-error'), 'retire grade', already);
}

/** Add a grade: true once its row shows. */
export async function addGrade(page: Page, grade: string): Promise<boolean> {
  const r = page.getByTestId(`grade-row-${grade.trim()}`);
  const already = grade.trim() !== '' && (await r.count()) > 0;
  await page.getByTestId('grade-add-code').fill(grade);
  await page.getByTestId('grade-add').click();
  return until(page, async () => grade.trim() !== '' && (await r.count()) > 0, page.getByTestId('grade-error'), 'add grade', already);
}

/** Merge a grade into another: true once the screen shows the merge done. */
export async function mergeGrade(page: Page, from: string, into: string): Promise<boolean> {
  const cell = page.getByTestId('grade-merged');
  const was = await shown(cell);
  try {
    await page.getByTestId('grade-merge-from').selectOption(from, { timeout: 2000 });
    await page.getByTestId('grade-merge-into').selectOption(into, { timeout: 2000 });
  } catch {
    return false;
  }
  await page.getByTestId('grade-merge').click();
  await page.waitForTimeout(300);
  return until(page, async () => (await shown(cell)) !== '' && ((await shown(cell)) !== was || was === ''),
    page.getByTestId('grade-error'), 'merge grade');
}

/** Show the sheet as if today were a date (this user's screen only). */
export async function setPreview(page: Page, date: string): Promise<void> {
  await page.getByTestId('preview-date').fill(date);
  await page.getByTestId('preview-set').click();
  await expect(page.getByTestId('sheet-preview')).toHaveText(date, { timeout: LOAD_WAIT });
}

export async function clearPreview(page: Page): Promise<void> {
  await page.getByTestId('preview-clear').click();
  await expect(page.getByTestId('sheet-preview')).toHaveText('', { timeout: LOAD_WAIT });
}

/** Withdraw a rate's newest version: true once its row's version or presence changed. */
export async function withdrawRate(page: Page, grade: string, from: string): Promise<boolean> {
  const r = page.getByTestId(`rate-row-${grade}-${from}`);
  const was = (await r.count()) > 0 ? `${await shown(r.getByTestId('rate-pct'))}|${await shown(r.getByTestId('rate-version'))}` : '';
  if ((await r.count()) === 0) return false;
  await r.getByTestId('rate-withdraw').click();
  return until(page, async () => ((await r.count()) > 0 ? `${await shown(r.getByTestId('rate-pct'))}|${await shown(r.getByTestId('rate-version'))}` : '') !== was,
    page.getByTestId('rate-form-error'), 'withdraw rate');
}

/** The policy version lines, in order. */
export async function policyLines(page: Page): Promise<string[]> {
  await page.getByTestId('policy-show').click();
  await page.waitForTimeout(300);
  return (await page.getByTestId('policy-line').allTextContents()).map((t) => t.trim());
}

async function downloadOf(page: Page, testid: string): Promise<string> {
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId(testid).click()]);
  const path = await download.path();
  if (!path) throw new Error('export produced no file');
  return readFileSync(path, 'utf8');
}

export async function exportPeriods(page: Page): Promise<string> { return downloadOf(page, 'export-periods'); }
export async function exportTree(page: Page): Promise<string> { return downloadOf(page, 'export-tree'); }
export async function exportPolicy(page: Page): Promise<string> { return downloadOf(page, 'export-policy'); }

export async function setRole(page: Page, user: string, role: string): Promise<boolean> {
  await page.getByTestId('role-form-user').fill(user);
  await page.getByTestId('role-form-role').fill(role);
  const r = page.getByTestId(`role-row-${user}`);
  const done = async () => (await r.count()) > 0 && await shown(r.getByTestId('role-value')) === role;
  const already = await done();
  await page.getByTestId('role-form-submit').click();
  return until(page, done, page.getByTestId('role-form-error'), 'set role', already);
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
