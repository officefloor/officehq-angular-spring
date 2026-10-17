// Drives the app through its screens, binding only to data-testid (AGENTS.md §2). Every test
// arranges its own data with these helpers and asserts through them or through data-testid
// directly. An action helper returns what the screen reported: an id when a row was created,
// true/false for whether the action went through or the screen refused it (its error element
// appeared). It throws when neither a result nor an error shows within the wait, so a screen that
// does nothing fails the test instead of passing as "refused".
import { expect, type Browser, type Locator, type Page } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';

const AUDIT_FILE = process.env.AUDIT_FILE ?? '../.run/audit.log';
const WAIT = 8000;

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

function idOf(testid: string | null, prefix: string): string {
  if (!testid || !testid.startsWith(prefix)) throw new Error(`no ${prefix}* id on ${testid}`);
  return testid.slice(prefix.length);
}

/** Poll until `done` holds (true) or `error` is visible (false); throw when neither happens. */
async function until(page: Page, done: () => Promise<boolean>, error: Locator, what: string): Promise<boolean> {
  const deadline = Date.now() + WAIT;
  while (Date.now() < deadline) {
    if (await done()) return true;
    if (await error.isVisible().catch(() => false)) return false;
    await page.waitForTimeout(50);
  }
  if (await done()) return true;
  throw new Error(`${what}: neither a result nor an error appeared within ${WAIT}ms`);
}

// ---- session ---------------------------------------------------------------------------------

export async function userPage(browser: Browser, user: string, sheet: string): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await page.goto('/');
  await page.getByTestId('signin-name').fill(user);
  await page.getByTestId('signin-submit').click();
  await expect(page.getByTestId('current-user')).toHaveText(user);
  await loadSheet(page, sheet);
  return page;
}

export async function loadSheet(page: Page, sheet: string): Promise<void> {
  await page.getByTestId('sheet-id-input').fill(sheet);
  await page.getByTestId('sheet-load').click();
  await expect(page.getByTestId('sheet-current-id')).toHaveText(sheet);
}

/** Re-load the active sheet so the screen shows what the store holds now. */
export async function refresh(page: Page): Promise<void> {
  const sheet = (await page.getByTestId('sheet-current-id').textContent() ?? '').trim();
  await loadSheet(page, sheet);
}

export async function setToday(page: Page, date: string): Promise<void> {
  await page.getByTestId('sheet-today-input').fill(date);
  await page.getByTestId('sheet-today-save').click();
  await expect(page.getByTestId('sheet-today')).toHaveText(date);
}

// ---- people ----------------------------------------------------------------------------------

export function row(page: Page, personId: string): Locator {
  return page.getByTestId(`person-row-${personId}`);
}

function rows(page: Page): Locator {
  return page.locator('[data-testid^="person-row-"]');
}

function rowsNamed(page: Page, name: string): Locator {
  const re = new RegExp(`^\\s*${escapeRe(name.trim())}\\s*$`);
  return rows(page).filter({ has: page.getByTestId('person-name').filter({ hasText: re }) });
}

/** The id of the (newest) person row showing this name. */
export async function personId(page: Page, name: string): Promise<string> {
  const r = rowsNamed(page, name).last();
  await expect(r).toBeVisible();
  return idOf(await r.getAttribute('data-testid'), 'person-row-');
}

async function fillPersonForm(page: Page, f: { name?: string; grade?: string; gradeFrom?: string }) {
  if (f.name !== undefined) await page.getByTestId('person-form-name').fill(f.name);
  if (f.grade !== undefined) await page.getByTestId('person-form-grade').selectOption(f.grade);
  if (f.gradeFrom !== undefined) await page.getByTestId('person-form-grade-from').fill(f.gradeFrom);
}

/** Add a person through the form. The new row's id, or null when the form refused. */
export async function addPerson(page: Page, f: { name: string; grade: string; gradeFrom?: string }): Promise<string | null> {
  const before = await rows(page).count();
  await fillPersonForm(page, f);
  await page.getByTestId('person-form-submit').click();
  const ok = await until(page, async () => (await rows(page).count()) > before,
    page.getByTestId('person-form-error'), 'add person');
  return ok ? personId(page, f.name) : null;
}

export async function editOpen(page: Page, personId: string): Promise<void> {
  await page.getByTestId(`person-edit-${personId}`).click();
  await expect(page.getByTestId('person-form-name')).not.toHaveValue('');
  editing.set(page, personId);
}

/** Save the open editor with these changes; true when saved, false when the form refused. */
export async function editSave(page: Page, f: { name?: string; grade?: string; gradeFrom?: string }): Promise<boolean> {
  const id = editing.get(page);
  if (!id) throw new Error('editSave without editOpen');
  await fillPersonForm(page, f);
  await page.getByTestId('person-form-submit').click();
  const r = row(page, id);
  return until(page, async () => {
    if (f.name !== undefined && (await r.getByTestId('person-name').textContent() ?? '').trim() !== f.name.trim()) return false;
    if (f.grade !== undefined && (await r.getByTestId('person-grade').textContent() ?? '').trim() !== f.grade) return false;
    return !(await page.getByTestId('person-form-error').isVisible().catch(() => false));
  }, page.getByTestId('person-form-error'), 'save person');
}

export async function editPerson(page: Page, personId: string, f: { name?: string; grade?: string; gradeFrom?: string }): Promise<boolean> {
  await editOpen(page, personId);
  return editSave(page, f);
}

export async function retire(page: Page, personId: string, date: string): Promise<boolean> {
  const r = row(page, personId);
  await r.getByTestId('person-retire-date').fill(date);
  await r.getByTestId('person-retire').click();
  return until(page, async () => (await r.count()) === 0
    || (await r.getByTestId('person-retired').textContent() ?? '').trim() === 'yes',
    r.getByTestId('person-error'), 'retire');
}

/** Toggle the hold (no dates), or add a hold with dates: true once the row shows it. */
export async function hold(page: Page, personId: string, f: { from?: string; to?: string }): Promise<boolean> {
  const r = row(page, personId);
  const dated = f.from !== undefined || f.to !== undefined || (await r.getByTestId('person-hold-from').count()) > 0;
  const wasText = (await r.getByTestId('person-hold').textContent() ?? '').trim();
  const lines = r.getByTestId('person-hold-line');
  const wasLines = await lines.count();
  if (f.from !== undefined) await r.getByTestId('person-hold-from').fill(f.from);
  if (f.to !== undefined) await r.getByTestId('person-hold-to').fill(f.to);
  await r.getByTestId('person-hold-toggle').click();
  return until(page, async () => dated
    ? (await lines.count()) > wasLines
    : (await r.getByTestId('person-hold').textContent() ?? '').trim() !== wasText,
    r.getByTestId('person-error'), 'hold');
}

export async function showRetired(page: Page, on: boolean): Promise<void> {
  await page.getByTestId('show-retired').setChecked(on);
}

export async function addPatron(page: Page, personId: string, patronName: string): Promise<boolean> {
  const r = row(page, personId);
  const names = async () => (await r.getByTestId('person-patrons').textContent() ?? '').split(',').filter((s) => s.trim()).length;
  const before = await names();
  try {
    await r.getByTestId('person-patron-select').selectOption({ label: patronName }, { timeout: 2000 });
  } catch {
    return false;             // not offered: the screen refuses it by leaving it out
  }
  await r.getByTestId('person-patron-add').click();
  return until(page, async () => (await names()) > before, r.getByTestId('person-patron-error'), 'add patron');
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

type ClaimFields = { amount?: string; date?: string; note?: string; corrects?: string };

async function fillClaimForm(r: Locator, f: ClaimFields) {
  if (f.amount !== undefined) await r.getByTestId('claim-form-amount').fill(f.amount);
  if (f.date !== undefined) await r.getByTestId('claim-form-date').fill(f.date);
  if (f.note !== undefined) await r.getByTestId('claim-form-note').fill(f.note);
  if (f.corrects !== undefined) await r.getByTestId('claim-form-corrects').fill(f.corrects);
}

async function newestClaim(r: Locator): Promise<string> {
  return idOf(await claimRows(r).first().getAttribute('data-testid'), 'claim-row-');
}

/** Add a claim from the person's row. The new claim's id (listed newest first), or null. */
export async function addClaim(page: Page, personId: string, f: ClaimFields & { amount: string }): Promise<string | null> {
  const r = row(page, personId);
  const before = await claimRows(r).count();
  await fillClaimForm(r, f);
  await r.getByTestId('claim-form-submit').click();
  const ok = await until(page, async () => (await claimRows(r).count()) > before,
    r.getByTestId('claim-form-error'), 'add claim');
  return ok ? newestClaim(r) : null;
}

/** Submit the claim form twice in quick succession (a double click on submit). */
export async function addClaimTwice(page: Page, personId: string, f: ClaimFields & { amount: string }): Promise<string | null> {
  const r = row(page, personId);
  const before = await claimRows(r).count();
  await fillClaimForm(r, f);
  await r.getByTestId('claim-form-submit').dblclick();
  const ok = await until(page, async () => (await claimRows(r).count()) > before,
    r.getByTestId('claim-form-error'), 'add claim twice');
  await page.waitForTimeout(500);          // let a second record land, if the app stores one
  return ok ? newestClaim(r) : null;
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
      const cell = (await c.getByTestId(`claim-${k}`).textContent() ?? '').trim();
      if (cell !== v) return false;
    }
    return true;
  }, error, 'edit claim');
}

/** open | settle | void | approve | reject(reason): true when the status changed. */
export async function claimAction(page: Page, claimId: string, action: string, reason?: string): Promise<boolean> {
  const c = claimRow(page, claimId);
  const was = (await c.getByTestId('claim-status').textContent() ?? '').trim();
  if (reason !== undefined) await c.getByTestId('claim-reject-reason').fill(reason);
  await c.getByTestId(`claim-${action}`).click();
  return until(page, async () => (await c.getByTestId('claim-status').textContent() ?? '').trim() !== was,
    c.getByTestId('claim-error'), `claim ${action}`);
}

export async function numbersOf(page: Page, claimIds: string[]): Promise<string[]> {
  await refresh(page);
  const out: string[] = [];
  for (const id of claimIds) out.push((await claimRow(page, id).getByTestId('claim-number').textContent() ?? '').trim());
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
  await expect(page.getByTestId('bulk-opened')).toBeVisible();
  return {
    opened: Number((await page.getByTestId('bulk-opened').textContent() ?? '').trim()),
    refused: Number((await page.getByTestId('bulk-refused').textContent() ?? '').trim()),
  };
}

/** The quick-add bar: the new claim's id, or null when refused. */
export async function quickAdd(page: Page, f: { name: string; amount: string }): Promise<string | null> {
  const before = await claimRows(page).count();
  await page.getByTestId('quick-name').fill(f.name);
  await page.getByTestId('quick-amount').fill(f.amount);
  await page.getByTestId('quick-submit').click();
  const ok = await until(page, async () => (await claimRows(page).count()) > before,
    page.getByTestId('quick-error'), 'quick add');
  if (!ok) return null;
  return newestClaim(rowsNamed(page, f.name).last());
}

// ---- rates, periods, settings, roles, import, export ----------------------------------------

export async function setRate(page: Page, f: { grade: string; pct: string; from?: string }): Promise<boolean> {
  await page.getByTestId('rate-form-grade').selectOption(f.grade);
  await page.getByTestId('rate-form-pct').fill(f.pct);
  if (f.from !== undefined) await page.getByTestId('rate-form-from').fill(f.from);
  await page.getByTestId('rate-form-submit').click();
  const r = page.getByTestId(`rate-row-${f.grade}-${f.from ?? 'start'}`);
  return until(page, async () => (await r.getByTestId('rate-pct').textContent() ?? '').trim() === f.pct,
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

/** close | reopen: true when the period's closed flag changed. */
export async function periodAction(page: Page, name: string, action: string): Promise<boolean> {
  const r = page.getByTestId(`period-row-${name}`);
  const was = (await r.getByTestId('period-closed').textContent() ?? '').trim();
  await r.getByTestId(`period-${action}`).click();
  return until(page, async () => (await r.getByTestId('period-closed').textContent() ?? '').trim() !== was,
    r.getByTestId('period-error'), `period ${action}`);
}

export async function setSetting(page: Page, key: string, value: string): Promise<void> {
  await page.getByTestId(`setting-${key}`).fill(value);
  await page.getByTestId('settings-save').click();
  await expect(page.getByTestId(`setting-${key}-current`)).toHaveText(value);
}

export async function setRole(page: Page, user: string, role: string): Promise<boolean> {
  await page.getByTestId('role-form-user').fill(user);
  await page.getByTestId('role-form-role').fill(role);
  await page.getByTestId('role-form-submit').click();
  const r = page.getByTestId(`role-row-${user}`);
  return until(page, async () => (await r.getByTestId('role-value').textContent() ?? '').trim() === role,
    page.getByTestId('role-form-error'), 'set role');
}

/** Paste lines into the import box. Counts shown and the line numbers marked as errors. */
export async function importPeople(page: Page, text: string): Promise<{ added: number; updated?: number; errors: number[] }> {
  await page.getByTestId('import-text').fill(text);
  await page.getByTestId('import-submit').click();
  await expect(page.getByTestId('import-added')).toBeVisible();
  const out: { added: number; updated?: number; errors: number[] } = {
    added: Number((await page.getByTestId('import-added').textContent() ?? '').trim()), errors: [],
  };
  if ((await page.getByTestId('import-updated').count()) > 0) {
    out.updated = Number((await page.getByTestId('import-updated').textContent() ?? '').trim());
  }
  const marks = page.locator('[data-testid^="import-error-"]');
  for (let i = 0; i < await marks.count(); i++) {
    out.errors.push(Number(idOf(await marks.nth(i).getAttribute('data-testid'), 'import-error-')));
  }
  out.errors.sort((a, b) => a - b);
  return out;
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
 *  the expected ones (a later feature may extend a line, never change or reorder its fields). */
export function expectAudit(sheet: string, expected: string[]): void {
  const lines = auditFor(sheet);
  expect(lines.length, `audit lines for ${sheet}:\n${lines.join('\n')}`).toBe(expected.length);
  lines.forEach((line, i) => {
    const e = expected[i];
    const ok = line === e || line.startsWith(e + ' ');
    expect(ok, `audit line ${i + 1}\n  expected: ${e}\n  actual:   ${line}`).toBe(true);
  });
}
