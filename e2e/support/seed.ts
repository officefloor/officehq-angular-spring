// Per-test data setup (AGENTS.md §4). Each test RESETS then SEEDS via the app's /__test__
// endpoints — this is Arrange, not Assert. Tests still ASSERT only through the UI (data-testid).
//
// FAIL LOUD: reset and seed are the Arrange for EVERY test, so they are a hard precondition — if
// either does not return 2xx the data is not in the state the test assumes, and a silent failure
// would surface later as confusing, misattributed assertion failures. We throw here so the break is
// attributed to the seed.
//
// TEST-DATA CONTRACT (AGENTS.md §4.4): before the first test seeds anything, the app's test data
// sets (.run/fixture-contract.json — data only) are checked: reset, seed, read back with
// GET /__test__/fixture, and every seeded field must come back with the same value. That catches a
// seed endpoint that stopped storing a field some data set relies on.
//   SEED_CONTRACT=enforce (default)  a failure fails the test that triggered the check
//   SEED_CONTRACT=report             record the result, never fail a test
//   SEED_CONTRACT=off                skip
import { request, type APIRequestContext, type APIResponse } from '@playwright/test';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const BASE = process.env.BASE_URL ?? `http://localhost:${process.env.PORT ?? 3000}`;
// Playwright runs from e2e/, so the app's .run/ is one level up (as audit.ts assumes).
const RUN_DIR = process.env.RUN_DIR ?? resolve(process.cwd(), '..', '.run');
const CONTRACT_FILE = process.env.FIXTURE_CONTRACT ?? resolve(RUN_DIR, 'fixture-contract.json');
const RESULT_FILE = process.env.SEED_CONTRACT_RESULT ?? resolve(RUN_DIR, 'seed-contract-result.json');
const MODE = (process.env.SEED_CONTRACT ?? 'enforce').toLowerCase();

async function must(res: Promise<APIResponse>, what: string): Promise<APIResponse> {
  const r = await res;
  if (!r.ok()) {
    const body = (await r.text().catch(() => '')).slice(0, 500);
    throw new Error(`seed contract broken: ${what} returned HTTP ${r.status()} — ${body}`);
  }
  return r;
}

export async function resetAndSeed(fixture: unknown): Promise<void> {
  const api = await request.newContext({ baseURL: BASE });
  try {
    await checkContractOnce(api);
    await must(api.post('/__test__/reset'), 'POST /__test__/reset');
    await must(api.post('/__test__/seed', { data: fixture }), 'POST /__test__/seed');
  } finally {
    await api.dispose();
  }
}

// ---- test-data contract check --------------------------------------------------------------

type DataSet = { name: string; test: string; fixture: Record<string, unknown> };
type Result = { contract_sha256: string; run: number; ok: boolean; checked: number; failures: string[] };

let checked: Promise<Result | null> | null = null;

async function checkContractOnce(api: APIRequestContext): Promise<void> {
  if (MODE === 'off' || !existsSync(CONTRACT_FILE)) return;
  // Once per Playwright run. A worker restarts after a failed test, so the result file is the
  // marker, not just this module's state — keyed by the contract AND the run (the worker's parent
  // is the Playwright runner). A later `bin/e2e` is a new run and checks again, so a fix to the
  // seed endpoint is seen at once instead of the previous run's failure being re-reported.
  checked ??= (async () => {
    const raw = readFileSync(CONTRACT_FILE, 'utf8');
    const sha = createHash('sha256').update(raw).digest('hex');
    if (existsSync(RESULT_FILE)) {
      const prev = JSON.parse(readFileSync(RESULT_FILE, 'utf8')) as Result;
      if (prev.contract_sha256 === sha && prev.run === process.ppid) return prev;
    }
    const sets = (JSON.parse(raw).data_sets ?? []) as DataSet[];
    const res: Result = { contract_sha256: sha, run: process.ppid, ok: true, checked: sets.length, failures: [] };
    if (sets.length) res.failures = await checkSets(api, sets);
    res.ok = res.failures.length === 0;
    mkdirSync(dirname(RESULT_FILE), { recursive: true });
    writeFileSync(RESULT_FILE, JSON.stringify(res, null, 2));
    return res;
  })();
  const res = await checked;
  if (res && !res.ok && MODE === 'enforce') {
    throw new Error(`test-data contract: ${res.failures.length} problem(s) (AGENTS.md §4.4)\n`
      + res.failures.slice(0, 20).join('\n'));
  }
}

class ReadBackMissing extends Error {}

async function readBack(api: APIRequestContext): Promise<unknown> {
  const r = await must(api.get('/__test__/fixture'), 'GET /__test__/fixture');
  // An unimplemented route typically falls through to the SPA's index.html with a 200.
  if (!(r.headers()['content-type'] ?? '').includes('json')) {
    throw new ReadBackMissing('GET /__test__/fixture does not return JSON — implement the read-back '
      + '(AGENTS.md §4.3) so the test data sets can be checked');
  }
  return r.json();
}

async function checkSets(api: APIRequestContext, sets: DataSet[]): Promise<string[]> {
  const out: string[] = [];
  for (const set of sets) {
    const where = `  in the data set of "${set.test}" (${set.name})`;
    try {
      await must(api.post('/__test__/reset'), 'POST /__test__/reset');
      await must(api.post('/__test__/seed', { data: set.fixture }), 'POST /__test__/seed');
      for (const f of compare(set.fixture, await readBack(api), '')) out.push(`the seed did not keep ${f}\n${where}`);
    } catch (e) {
      if (e instanceof ReadBackMissing) return [e.message];     // one cause, not one per fixture
      out.push(`${(e as Error).message}\n${where}`);
    }
    if (out.length >= 50) break;
  }
  try {
    // A reset must really empty the data; an endpoint that echoes its input would not.
    await must(api.post('/__test__/reset'), 'POST /__test__/reset');
    if (!isEmpty(await readBack(api))) out.push('GET /__test__/fixture still returns rows after POST /__test__/reset');
  } catch (e) {
    out.push((e as Error).message);
  }
  return out;
}

// ---- comparison (AGENTS.md §4.4: numbers by value, dates as yyyy-mm-dd, rows by id) -----------

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const NUMERIC = /^-?\d+(\.\d+)?$/;
const show = (v: unknown) => (v === undefined ? '<missing>' : JSON.stringify(v));

function sameScalar(sent: unknown, got: unknown): boolean {
  if (sent === got) return true;
  if (got === undefined || got === null) return sent === null;
  if (typeof sent === 'number' || (typeof sent === 'string' && NUMERIC.test(sent))) {
    const a = Number(sent), b = Number(got);
    if (!Number.isNaN(a) && !Number.isNaN(b)) return Math.abs(a - b) < 1e-9;
  }
  if (typeof sent === 'string' && DATE.test(sent) && typeof got === 'string') return got.slice(0, 10) === sent;
  if (typeof sent === 'boolean') return got === sent || String(got) === String(sent);
  return false;
}

function compare(sent: unknown, got: unknown, path: string): string[] {
  if (Array.isArray(sent)) {
    if (!Array.isArray(got)) return [`${path || 'the data'} (sent a list, read back ${show(got)})`];
    const out: string[] = [];
    for (const row of sent) {
      if (row && typeof row === 'object' && !Array.isArray(row) && 'id' in row) {
        const id = (row as { id: unknown }).id;
        const match = got.find((g) => g && typeof g === 'object' && sameScalar(id, (g as { id?: unknown }).id));
        if (!match) out.push(`${path}[id=${id}] (row missing from the read-back)`);
        else out.push(...compare(row, match, `${path}[id=${id}]`));
      } else if (!got.some((g) => compare(row, g, path).length === 0)) {
        out.push(`${path}[] ${show(row)} (no matching row in the read-back)`);
      }
    }
    return out;
  }
  if (sent && typeof sent === 'object') {
    if (!got || typeof got !== 'object' || Array.isArray(got)) return [`${path || 'the data'} (read back ${show(got)})`];
    const out: string[] = [];
    for (const [k, v] of Object.entries(sent)) {
      out.push(...compare(v, (got as Record<string, unknown>)[k], path ? `${path}.${k}` : k));
    }
    return out;
  }
  return sameScalar(sent, got) ? [] : [`${path} (sent ${show(sent)}, read back ${show(got)})`];
}

function isEmpty(v: unknown): boolean {
  if (v === null || v === undefined) return true;
  if (Array.isArray(v)) return v.length === 0;
  if (typeof v === 'object') return Object.values(v as object).every((x) => !Array.isArray(x) || x.length === 0);
  return false;
}

// Usage in a test:
//   test.beforeEach(async () => { await resetAndSeed({ /* rows this test needs */ }); });
