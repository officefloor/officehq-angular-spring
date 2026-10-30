import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, signal } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { SettingsStore } from '../settings/settings';

export interface RateView {
  grade: string;
  pct: string;
  // The date the rate applies from; null for a rate that applies from the beginning of time.
  from: string | null;
  // The date the rate applies through; null for a rate with no end.
  to: string | null;
  // How many versions the rate has; the one shown is the newest, the one in force.
  version: number;
}

// The rates that apply to the claims added up to a mark (inclusive; null: with no end) and after the previous tier's:
// a forward-only rate applies only to the claims added after it was saved.
export interface RateTier {
  upTo: number | null;
  rates: RateView[];
}

export const GRADES = ['A', 'B', 'C'];

// What a candidate rate for a grade would change: how many claims would show a different due, and the sum of the
// differences (new due minus current due), with two decimals.
export interface RatePreview {
  count: number;
  diff: string;
}

// A grade a person has from its from-date; null for the first one given, which applies from the beginning of time.
export interface DatedGrade {
  grade: string;
  from: string | null;
}

/** The grade in force on the date: the one with the latest from-date on or before it; '' with none. */
export function gradeOn(grades: readonly DatedGrade[], date: string | null): string {
  let best: DatedGrade | undefined;
  for (const g of grades) {
    if (g.from !== null && (date === null || g.from > date)) continue;
    if (!best || (g.from !== null && (best.from === null || g.from > best.from))) best = g;
  }
  return best?.grade ?? '';
}

const PCT = /^\d{1,5}(\.\d{1,2})?$/;

/** Whole cents of a two-decimal value. */
function cents(value: string): bigint {
  const negative = value.startsWith('-');
  const [whole, frac = ''] = (negative ? value.slice(1) : value).split('.');
  const c = BigInt(whole) * 100n + BigInt((frac + '00').slice(0, 2));
  return negative ? -c : c;
}

/**
 * The due on an amount at a rate (both two-decimal strings): amount times rate divided by 100,
 * rounded half to even (or half up, when asked) to two decimals; null (n/a) when there is no rate.
 * Worked in whole numbers so nothing drifts.
 */
export function dueOn(amount: string, pct: string | undefined, halfUp = false): string | null {
  if (pct === undefined) return null;
  const product = cents(amount) * cents(pct);
  // rounded on the size, so a negative amount (a correction) rounds like its positive
  const size = product < 0n ? -product : product;
  let due = size / 10000n;
  const rest = size % 10000n;
  if (rest > 5000n || (rest === 5000n && (halfUp || due % 2n === 1n))) due += 1n;
  return formatCents(product < 0n ? -due : due);
}

/**
 * A holder's part of a shared claim: the amount times the share divided by 100, times the rate divided by 100 (all
 * two-decimal strings), rounded half to even (or half up, when asked) to two decimals; null (n/a) when there is no
 * rate.
 */
export function partOn(amount: string, share: string, pct: string | undefined, halfUp = false): string | null {
  if (pct === undefined) return null;
  const product = cents(amount) * cents(share) * cents(pct);
  const size = product < 0n ? -product : product;
  let part = size / 100000000n;
  const rest = size % 100000000n;
  if (rest > 50000000n || (rest === 50000000n && (halfUp || part % 2n === 1n))) part += 1n;
  return formatCents(product < 0n ? -part : part);
}

/** The two-decimal value, raised to the floor when it is below it. */
export function atLeast(value: string, floor: string): string {
  return cents(value) < cents(floor) ? formatCents(cents(floor)) : value;
}

/** The two-decimal value held to the cap when it is above it; n/a, or no cap (null), leaves it as it is. */
export function atMost(value: string | null, cap: string | null): string | null {
  return value !== null && cap !== null && cents(value) > cents(cap) ? formatCents(cents(cap)) : value;
}

/** What the two-decimal value is above the cap: 0.00 when it is not, is n/a, or there is no cap (null). */
export function excessOver(value: string | null, cap: string | null): string {
  return value !== null && cap !== null && cents(value) > cents(cap) ? formatCents(cents(value) - cents(cap)) : '0.00';
}

/** The cap less the cut total (both two decimals), not below 0.00; no cap (null) stays none. */
export function capLess(cap: string | null, cut: string): string | null {
  if (cap === null) return null;
  const room = cents(cap) - cents(cut);
  return formatCents(room < 0n ? 0n : room);
}

/** The sum of two-decimal values, skipping n/a. */
export function sumSkippingNa(values: readonly (string | null)[]): string {
  return formatCents(values.reduce((sum, v) => (v === null ? sum : sum + cents(v)), 0n));
}

/** One two-decimal figure minus another, as a two-decimal figure with a minus sign when negative. */
export function difference(value: string, minus: string): string {
  return formatCents(cents(value) - cents(minus));
}

/**
 * 100.00 split equally into the given number of two-decimal shares, each rounded half to even, with the remainder
 * (100.00 minus the shares added up) given to the first.
 */
export function equalShares(count: number): string[] {
  if (count <= 0) return [];
  const n = BigInt(count);
  let share = 10000n / n;
  const rest = 10000n % n;
  if (rest * 2n > n || (rest * 2n === n && share % 2n === 1n)) share += 1n;
  return Array.from({ length: count }, (_, i) => formatCents(i === 0 ? 10000n - share * (n - 1n) : share));
}

/** The sum of each two-decimal figure times its two-decimal share divided by 100, rounded half to even. */
export function sharedSum(parts: readonly { figure: string; share: string }[]): string {
  const product = parts.reduce((sum, p) => sum + cents(p.figure) * cents(p.share), 0n);
  const size = product < 0n ? -product : product;
  let total = size / 10000n;
  const rest = size % 10000n;
  if (rest > 5000n || (rest === 5000n && total % 2n === 1n)) total += 1n;
  return formatCents(product < 0n ? -total : total);
}

/**
 * A score: the two-decimal figure divided by 100, minus 5 for each of the count, rounded half to even to a whole
 * number, with a minus sign when negative.
 */
export function scoreOf(figure: string, minusFives: number): string {
  const value = cents(figure) - BigInt(minusFives) * 50000n;
  const size = value < 0n ? -value : value;
  let score = size / 10000n;
  const rest = size % 10000n;
  if (rest > 5000n || (rest === 5000n && score % 2n === 1n)) score += 1n;
  return (value < 0n && score > 0n ? '-' : '') + score.toString();
}

/**
 * The sheet-total of two-decimal figures, skipping n/a: with three or more figures, the single highest and the
 * single lowest are left out.
 */
export function trimmedSum(values: readonly (string | null)[]): string {
  const figures = values.filter((v): v is string => v !== null).map(cents);
  let sum = figures.reduce((total, c) => total + c, 0n);
  if (figures.length >= 3) {
    const max = figures.reduce((a, b) => (b > a ? b : a));
    const min = figures.reduce((a, b) => (b < a ? b : a));
    sum -= max + min;
  }
  return formatCents(sum);
}

/**
 * The percent of the rate in force on the date among one grade's rates: the one with the latest
 * from-date on or before it, a rate with no from-date applying from the beginning of time;
 * undefined (n/a) when none applies yet, or when the date is past that one's to-date.
 */
export function rateOn(rates: readonly RateView[], date: string | null): string | undefined {
  let best: RateView | undefined;
  for (const r of rates) {
    if (r.from !== null && (date === null || r.from > date)) continue;
    if (!best || (r.from !== null && (best.from === null || r.from > best.from))) best = r;
  }
  if (best?.to && date !== null && date > best.to) return undefined;
  return best?.pct;
}

function formatCents(c: bigint): string {
  const size = c < 0n ? -c : c;
  return `${c < 0n ? '-' : ''}${size / 100n}.${(size % 100n).toString().padStart(2, '0')}`;
}

/** The rates by grade, each grade's in the order given. */
function byGrade(rates: readonly RateView[]): ReadonlyMap<string, readonly RateView[]> {
  const by = new Map<string, RateView[]>();
  for (const r of rates) by.set(r.grade, [...(by.get(r.grade) ?? []), r]);
  return by;
}

/** Orders rates by grade, then from-date with no from-date first. */
function byGradeThenFrom(a: RateView, b: RateView): number {
  if (a.grade !== b.grade) return a.grade < b.grade ? -1 : 1;
  return (a.from ?? '').localeCompare(b.from ?? '');
}

// The rates set on the active sheet, each grade's by from-date; a grade with no rate is not in the map.
@Injectable({ providedIn: 'root' })
export class RateStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  // Every rate on the sheet, by grade and then from-date.
  readonly list = signal<readonly RateView[]>([]);
  // The rates that apply on the sheet: its own, and for a date on which a grade has none in force, its parent sheets'
  // (up to three levels up), given as rates that do not overlap; by grade and then from-date.
  readonly applying = signal<readonly RateView[]>([]);
  readonly rates = computed(() => byGrade(this.applying()));
  // The rates that apply on the sheet by the mark of the claims they apply to, in mark order; the last, with no end,
  // is the rates as they apply to claims added from now on.
  readonly tiers = signal<readonly RateTier[]>([]);
  // The rates that apply to a claim, by grade: those of the first tier its mark is within (a claim just added, with
  // no mark yet, is the newest). The rates panel and the rate in force today do not go by this.
  readonly byClaim = computed(() => {
    const tiers = this.tiers().map((t) => ({ upTo: t.upTo, rates: byGrade(t.rates) }));
    const newest = tiers.length ? tiers[tiers.length - 1].rates : this.rates();
    return (c: { mark: number | null }): ReadonlyMap<string, readonly RateView[]> =>
      (c.mark === null ? undefined : tiers.find((t) => t.upTo === null || c.mark! <= t.upTo))?.rates ?? newest;
  });
  // Each grade's rate in force on the sheet's today; a grade with none is not in the map.
  readonly current = computed(() => {
    const today = this.session.sheetToday();
    const by = new Map<string, string>();
    for (const [g, list] of this.rates()) {
      const pct = rateOn(list, today);
      if (pct !== undefined) by.set(g, pct);
    }
    return by as ReadonlyMap<string, string>;
  });
  // For each month of the sheet's today's calendar year (as yyyy-mm), each grade's rate in force on its first day;
  // a grade with none is not in that month's map. Empty with no today.
  readonly calendar = computed(() => {
    const year = this.session.sheetToday()?.slice(0, 4);
    if (!year) return [] as { month: string; rates: ReadonlyMap<string, string> }[];
    return Array.from({ length: 12 }, (_, i) => {
      const month = `${year}-${String(i + 1).padStart(2, '0')}`;
      const by = new Map<string, string>();
      for (const [g, list] of this.rates()) {
        const pct = rateOn(list, `${month}-01`);
        if (pct !== undefined) by.set(g, pct);
      }
      return { month, rates: by as ReadonlyMap<string, string> };
    });
  });

  /** Re-read the sheet's rates from the store. */
  async load(sheet: string): Promise<void> {
    this.list.set([]);
    this.applying.set([]);
    this.tiers.set([]);
    const base = `/api/sheets/${encodeURIComponent(sheet)}/rates`;
    const [list, applying, tiers] = await Promise.all([
      firstValueFrom(this.http.get<RateView[]>(base)),
      firstValueFrom(this.http.get<RateView[]>(`${base}/applying`)),
      firstValueFrom(this.http.get<RateTier[]>(`${base}/tiers`)),
    ]);
    if (this.session.sheetId() === sheet) {
      this.list.set([...list].sort(byGradeThenFrom));
      this.applying.set([...applying].sort(byGradeThenFrom));
      this.tiers.set(tiers);
    }
  }

  /** Re-read the rates that apply on the sheet, its parent sheets' filling in for its own, and their tiers. */
  private async loadApplying(sheet: string): Promise<void> {
    const base = `/api/sheets/${encodeURIComponent(sheet)}/rates`;
    const [applying, tiers] = await Promise.all([
      firstValueFrom(this.http.get<RateView[]>(`${base}/applying`)),
      firstValueFrom(this.http.get<RateTier[]>(`${base}/tiers`)),
    ]);
    if (this.session.sheetId() === sheet) {
      this.applying.set([...applying].sort(byGradeThenFrom));
      this.tiers.set(tiers);
    }
  }

  /**
   * Set the grade's rate from the date (null: from the beginning of time) through the to-date (null: with no end),
   * replacing the one it had for that from-date.
   */
  async set(sheet: string, grade: string, pct: string, from: string | null, to: string | null): Promise<void> {
    const r = await firstValueFrom(
      this.http.put<RateView>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}`, { pct, from, to }),
    );
    if (this.session.sheetId() === sheet) {
      this.list.update((l) => [...l.filter((x) => x.grade !== r.grade || x.from !== r.from), r].sort(byGradeThenFrom));
      await this.loadApplying(sheet);
    }
  }

  /**
   * Withdraw the newest version of the grade's rate for the from-date (null: from the beginning of time): the version
   * before it applies again, and with none the rate is gone.
   */
  async withdraw(sheet: string, grade: string, from: string | null): Promise<void> {
    const params: Record<string, string> = from === null ? {} : { from };
    const r = await firstValueFrom(
      this.http.post<RateView | null>(
        `/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}/withdraw`,
        null,
        { params },
      ),
    );
    if (this.session.sheetId() === sheet) {
      this.list.update((l) =>
        [...l.filter((x) => x.grade !== grade || x.from !== from), ...(r ? [r] : [])].sort(byGradeThenFrom),
      );
      await this.loadApplying(sheet);
    }
  }

  /**
   * Set a rate for each line of grade,rate,from,to, in order, all of them or none; the store checks every line by the
   * rate form's rules first. Resolves to how many were set.
   */
  async importRates(sheet: string, text: string): Promise<number> {
    const set = await firstValueFrom(
      this.http.post<RateView[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates/import`, { text }),
    );
    if (this.session.sheetId() === sheet) await this.load(sheet);
    return set.length;
  }

  /** Preview the grade's rate at the percent on every date; nothing is saved. */
  preview(sheet: string, grade: string, pct: string): Promise<RatePreview> {
    return firstValueFrom(
      this.http.post<RatePreview>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}/preview`, { pct }),
    );
  }
}

// The rate in force for each grade on the sheet's today, every rate set, and the form that sets one.
@Component({
  selector: 'app-rates',
  imports: [FigPipe],
  template: `
    <section aria-label="Rates">
      <h2>Rates</h2>
      <form (submit)="submit($event)" aria-label="Set rate">
        <label for="rate-grade">Grade</label>
        <select
          id="rate-grade"
          data-testid="rate-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="rate-pct">Rate (%)</label>
        <input
          id="rate-pct"
          data-testid="rate-form-pct"
          inputmode="decimal"
          [value]="pct()"
          (input)="pct.set(value($event)); error.set(null)"
        />
        <label for="rate-from">From (optional)</label>
        <input
          id="rate-from"
          type="date"
          data-testid="rate-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="rate-to">To (optional)</label>
        <input
          id="rate-to"
          type="date"
          data-testid="rate-form-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err | fig }}</p>
        }
      </form>

      <form (submit)="importRates($event)" aria-label="Import rates">
        <label for="import-rates">Import rates, one per line as grade,rate,from,to</label>
        <textarea
          id="import-rates"
          data-testid="import-rates"
          rows="4"
          [value]="importText()"
          (input)="importText.set(value($event)); importError.set(null)"
        ></textarea>
        <button type="submit" data-testid="import-rates-submit">Import rates</button>
        @if (importSet() !== null) {
          <p role="status">Set: <strong data-testid="import-rates-set">{{ importSet() | fig }}</strong></p>
        }
        @if (importError(); as err) {
          <p role="alert" data-testid="import-rates-error">{{ err | fig }}</p>
        }
      </form>

      <form (submit)="runPreview($event)" aria-label="Preview rate">
        <label for="rate-preview-grade">Grade</label>
        <select
          id="rate-preview-grade"
          data-testid="rate-preview-grade"
          [value]="previewGrade()"
          (change)="previewGrade.set(value($event)); clearPreview()"
        >
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === previewGrade()">{{ g }}</option>
          }
        </select>
        <label for="rate-preview-pct">Candidate rate (%)</label>
        <input
          id="rate-preview-pct"
          data-testid="rate-preview-pct"
          inputmode="decimal"
          [value]="previewPct()"
          (input)="previewPct.set(value($event)); clearPreview()"
        />
        <button type="submit" data-testid="rate-preview">Preview</button>
        @if (previewResult(); as r) {
          <p>
            Claims changed: <span data-testid="rate-preview-count">{{ r.count | fig }}</span>;
            difference: <span data-testid="rate-preview-diff">{{ r.diff | fig }}</span>
          </p>
        }
        @if (previewError(); as err) {
          <p role="alert" data-testid="rate-preview-error">{{ err | fig }}</p>
        }
      </form>

      <table>
        <caption>In force on {{ session.sheetToday() }}</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th><th scope="col">Versions</th></tr>
        </thead>
        <tbody>
          @for (g of grades(); track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ (store.current().get(g) ?? 'n/a') | fig }}</td>
            </tr>
          }
        </tbody>
      </table>

      <table>
        <caption>Rate calendar: in force on the first of each month</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            @for (m of store.calendar(); track m.month) {
              <th scope="col">{{ m.month }}</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (g of grades(); track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              @for (m of store.calendar(); track m.month) {
                <td [attr.data-testid]="'rate-month-' + g + '-' + m.month">{{ (m.rates.get(g) ?? 'n/a') | fig }}</td>
              }
            </tr>
          }
        </tbody>
      </table>

      <table>
        <caption>All rates</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Rate (%)</th><th scope="col">Versions</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (r of store.list(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ (r.from ?? 'the start') | fig }}</td>
              <td data-testid="rate-to">{{ (r.to ?? '') | fig }}</td>
              <td data-testid="rate-pct">{{ r.pct | fig }}</td>
              <td data-testid="rate-version">{{ r.version | fig }}</td>
              <td>
                <button
                  type="button"
                  data-testid="rate-withdraw"
                  [attr.aria-label]="'Withdraw the newest version of grade ' + r.grade + ' from ' + (r.from ?? 'the start')"
                  (click)="withdraw(r)"
                >
                  Withdraw
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Rates {
  protected readonly session = inject(Session);
  protected readonly store = inject(RateStore);

  /** The sheet's grades, A, B and C then those added. */
  protected readonly grades = inject(SettingsStore).grades;
  protected readonly grade = signal('A');
  protected readonly pct = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly previewGrade = signal('A');
  protected readonly previewPct = signal('');
  protected readonly previewResult = signal<RatePreview | null>(null);
  protected readonly previewError = signal<string | null>(null);
  protected readonly importText = signal('');
  protected readonly importSet = signal<number | null>(null);
  protected readonly importError = signal<string | null>(null);
  // A submit in flight: a second submit before it answers is ignored, so it stores one rate.
  private submitting = false;
  // An import in flight: a second press before it answers is ignored, so it sets each line once.
  private importing = false;

  constructor() {
    // a preview and an import's count belong to the sheet as it was loaded; a load clears them
    effect(() => {
      this.session.loads();
      this.clearPreview();
      this.importText.set('');
      this.importSet.set(null);
      this.importError.set(null);
    });
  }

  protected clearPreview(): void {
    this.previewResult.set(null);
    this.previewError.set(null);
  }

  protected async runPreview(e: Event): Promise<void> {
    e.preventDefault();
    this.clearPreview();
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const pct = this.previewPct().trim();
    if (!PCT.test(pct)) {
      this.previewError.set('Enter a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    if (!this.grades().includes(this.previewGrade())) {
      this.previewError.set('Choose one of the grades.');
      return;
    }
    try {
      const r = await this.store.preview(sheet, this.previewGrade(), pct);
      if (this.session.sheetId() === sheet) this.previewResult.set(r);
    } catch {
      this.previewError.set('Could not preview that rate.');
    }
  }

  /**
   * Sets a rate for each line of the import box, in order; the store checks every line by the rate form's rules first
   * and sets none when any is refused. Shows how many were set.
   */
  protected async importRates(e: Event): Promise<void> {
    e.preventDefault();
    if (this.importing) return;
    this.importSet.set(null);
    this.importError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = this.importText();
    if (!text.trim()) {
      this.importError.set('Paste at least one line of grade,rate,from,to.');
      return;
    }
    this.importing = true;
    try {
      const count = await this.store.importRates(sheet, text);
      if (this.session.sheetId() !== sheet) return;
      this.importText.set('');
      this.importSet.set(count);
    } catch (err) {
      const reason = err instanceof HttpErrorResponse ? (err.error as { message?: string } | null)?.message : null;
      this.importError.set(reason ? reason : 'Could not import those rates; none were set.');
    } finally {
      this.importing = false;
    }
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  /** Withdraws the rate's newest version; only a steward may, anyone else is shown the refusal. */
  protected async withdraw(r: RateView): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    this.submitting = true;
    try {
      await this.store.withdraw(sheet, r.grade, r.from);
    } catch (err) {
      const reason = err instanceof HttpErrorResponse ? (err.error as { message?: string } | null)?.message : null;
      this.error.set(reason ? reason : 'Could not withdraw that rate.');
    } finally {
      this.submitting = false;
    }
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const pct = this.pct().trim();
    if (!PCT.test(pct)) {
      this.error.set('Enter a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    if (!this.grades().includes(this.grade())) {
      this.error.set('Choose one of the grades.');
      return;
    }
    const from = this.from().trim();
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.error.set('Enter a from-date as yyyy-mm-dd.');
      return;
    }
    const to = this.to().trim();
    if (to && !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      this.error.set('Enter a to-date as yyyy-mm-dd.');
      return;
    }
    if (to && from && to < from) {
      this.error.set('Enter a to-date on or after the from-date.');
      return;
    }
    this.submitting = true;
    try {
      await this.store.set(sheet, this.grade(), pct, from || null, to || null);
    } catch {
      this.error.set('Could not set that rate.');
    } finally {
      this.submitting = false;
    }
  }
}
