import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { FigurePipe } from '../../core/locale';
import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** What a candidate rate would change: how many claims' dues, and the sum of new minus current due. */
export interface RatePreview {
  count: number;
  /** Two decimals, negative when lower. */
  diff: string;
}

export interface Rate {
  grade: string;
  /** A percent with two decimals, e.g. "12.50". */
  pct: string;
  /** The first day the rate applies (yyyy-mm-dd); null when it applies from the beginning of time. */
  from: string | null;
  /** The last day the rate applies (yyyy-mm-dd); null when it has no end. */
  to: string | null;
  /** How many versions the rate has had; this one, the newest, is the one in force. */
  version: number;
  /** 0 for the sheet's own rate, 1 for its parent sheet's, 2 for that sheet's parent's, and so on. */
  level: number;
  /**
   * Set when the rate is forward-only (saved with a from-date on or after the sheet's today): the highest claim id
   * there was when it was saved; only a claim whose rateStamp is above it sees it. Null when every claim does.
   */
  forwardAfter: number | null;
  /** Its older versions, newest first, that a claim not seeing it falls back on (empty unless it is forward-only). */
  older: OlderRate[];
}

/** An older version of a forward-only rate. */
export interface OlderRate {
  pct: string;
  to: string | null;
  forwardAfter: number | null;
}

/** Whether a claim with the stamp sees a setting with the mark; any does without a stamp. */
function seen(forwardAfter: number | null, stamp: number | undefined): boolean {
  return forwardAfter === null || stamp === undefined || stamp > forwardAfter;
}

/** The rate as the claim with the stamp sees it: itself, else its newest older version it sees; undefined with none. */
function seenBy(rate: Rate, stamp: number | undefined): Rate | undefined {
  if (seen(rate.forwardAfter, stamp)) return rate;
  const older = (rate.older ?? []).find((v) => seen(v.forwardAfter, stamp));
  return older === undefined ? undefined : { ...rate, pct: older.pct, to: older.to, forwardAfter: older.forwardAfter };
}

/**
 * The percent of the grade's rate in force on the date: the one with the latest from-date on or
 * before it, an undated rate counting as from the beginning of time; undefined when the date is
 * before every rate, or after that rate's to-date. With none of the sheet's own in force, the
 * parent sheet's, then its parent's, and so on (nearest level first). For a claim, given its rateStamp: a
 * forward-only rate saved before the claim was added is left out, falling back on its older version the claim sees.
 */
export function rateOn(rates: Rate[], grade: string, date: string, stamp?: number): string | undefined {
  const levels = [...new Set(rates.map((r) => r.level))].sort((a, b) => a - b);
  for (const level of levels) {
    const seenRates = rates.flatMap((r) => (r.level === level ? (seenBy(r, stamp) ?? []) : []));
    const pct = rateOnLevel(seenRates, grade, date);
    if (pct !== undefined) return pct;
  }
  return undefined;
}

function rateOnLevel(rates: Rate[], grade: string, date: string): string | undefined {
  let found: Rate | undefined;
  for (const r of rates) {
    if (r.grade !== grade || (r.from !== null && r.from > date)) continue;
    if (found === undefined || (found.from ?? '') < (r.from ?? '')) found = r;
  }
  return found === undefined || (found.to !== null && found.to < date) ? undefined : found.pct;
}

/** Hundredths of a unit in a decimal with at most two decimals, maybe negative ("10.25" is 1025n, "-0.30" is -30n). */
function hundredths(value: string): bigint {
  const trimmed = value.trim();
  const negative = trimmed.startsWith('-');
  const [whole, fraction = ''] = (negative ? trimmed.slice(1) : trimmed).split('.');
  const size = BigInt(whole || '0') * 100n + BigInt((fraction + '00').slice(0, 2));
  return negative ? -size : size;
}

/** Hundredths of a unit as a decimal with two decimals ("-30n" is "-0.30"). */
function decimalOf(cents: bigint): string {
  const size = cents < 0n ? -cents : cents;
  return `${cents < 0n ? '-' : ''}${size / 100n}.${(size % 100n).toString().padStart(2, '0')}`;
}

/** How a figure rounds a half cent: up (away from zero), or to the even cent. */
export type Rounding = 'half-up' | 'half-even';

/** An amount times a percent, divided by 100 and rounded to two decimals as given; n/a with no rate. */
export function dueOf(amount: string, pct: string | undefined, rounding: Rounding = 'half-even'): string {
  if (pct === undefined) return 'n/a';
  return percentOf(amount, pct, 1, rounding);
}

/** The pct percent of the amount, times the given count, rounded to cents, a half up or to the even cent. */
export function percentOf(amount: string, pct: string, times: number, rounding: Rounding = 'half-even'): string {
  // cents times hundredths of a percent are millionths of a unit: round them to cents.
  // A negative amount (a correction) rounds as its size does, so a half goes away from zero, or to the even cent.
  const signed = hundredths(amount) * hundredths(pct) * BigInt(times);
  const millionths = signed < 0n ? -signed : signed;
  const rest = millionths % 10000n;
  let cents = millionths / 10000n;
  if (rest > 5000n || (rest === 5000n && (rounding === 'half-up' || cents % 2n === 1n))) cents += 1n;
  return decimalOf(signed < 0n ? -cents : cents);
}

/** The due raised to the floor when below it; n/a, or no floor, leaves it as it is. */
export function floored(due: string, floor: string | null): string {
  if (due === 'n/a' || floor === null) return due;
  return hundredths(due) < hundredths(floor) ? floor : due;
}

/** The due-total held down to the cap when above it; n/a, or no cap, leaves it as it is. */
export function capped(dueTotal: string, cap: string | null): string {
  if (dueTotal === 'n/a' || cap === null) return dueTotal;
  return hundredths(dueTotal) > hundredths(cap) ? cap : dueTotal;
}

/** How far the due-total is above the cap: 0.00 when it is not, or is n/a, or there is no cap. */
export function excessOver(dueTotal: string, cap: string | null): string {
  if (dueTotal === 'n/a' || cap === null || hundredths(dueTotal) <= hundredths(cap)) return '0.00';
  return decimalOf(hundredths(dueTotal) - hundredths(cap));
}

/** The figure less the one before it, exactly to two decimals, with a minus sign when lower. */
export function changeFrom(figure: string, before: string): string {
  return decimalOf(hundredths(figure) - hundredths(before));
}

/** Adds dues with two decimals exactly, a correction's negative ones too, skipping the n/a ones. */
export function sumDues(dues: string[]): string {
  return decimalOf(dues.filter((d) => d !== 'n/a').reduce((total, d) => total + hundredths(d), 0n));
}

/**
 * Each figure times its share divided by 100, added up exactly and rounded half to even to two decimals, a negative
 * total as its size does; skipping the n/a figures.
 */
export function sumShared(figures: { figure: string; share: string }[]): string {
  // cents times hundredths of a percent are millionths of a unit: add them up, then round to cents.
  const signed = figures
    .filter((f) => f.figure !== 'n/a')
    .reduce((total, f) => total + hundredths(f.figure) * hundredths(f.share), 0n);
  const millionths = signed < 0n ? -signed : signed;
  const rest = millionths % 10000n;
  let cents = millionths / 10000n;
  if (rest > 5000n || (rest === 5000n && cents % 2n === 1n)) cents += 1n;
  return decimalOf(signed < 0n ? -cents : cents);
}

/** As {@link sumDues}, but leaving out the single highest and lowest figure when there are three or more. */
export function trimmedSumDues(dues: string[]): string {
  const figures = dues.filter((d) => d !== 'n/a').map(hundredths);
  if (figures.length < 3) return sumDues(dues);
  const sorted = [...figures].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  return decimalOf(sorted.slice(1, -1).reduce((total, d) => total + d, 0n));
}

// The rate in force on the sheet's today for each grade, every rate with its from-date, and the form
// that sets a grade's rate from a date.
@Component({
  selector: 'app-rates',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form (submit)="submit($event)">
        <label for="rate-form-grade">Grade</label>
        <select
          id="rate-form-grade"
          data-testid="rate-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === grade()">{{ g | fig }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input
          id="rate-form-pct"
          data-testid="rate-form-pct"
          inputmode="decimal"
          [value]="pct()"
          (input)="pct.set(value($event)); error.set(null)"
        />
        <label for="rate-form-from">From (blank: always)</label>
        <input
          id="rate-form-from"
          data-testid="rate-form-from"
          type="date"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="rate-form-to">To (blank: no end)</label>
        <input
          id="rate-form-to"
          data-testid="rate-form-to"
          type="date"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as message) {
          <p role="alert" data-testid="rate-form-error">{{ message | fig }}</p>
        }
      </form>
      <form (submit)="preview($event)">
        <label for="rate-preview-grade">Preview grade</label>
        <select
          id="rate-preview-grade"
          data-testid="rate-preview-grade"
          [value]="previewGrade()"
          (change)="previewGrade.set(value($event)); previewResult.set(null); previewError.set(null)"
        >
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === previewGrade()">{{ g | fig }}</option>
          }
        </select>
        <label for="rate-preview-pct">Candidate rate (%)</label>
        <input
          id="rate-preview-pct"
          data-testid="rate-preview-pct"
          inputmode="decimal"
          [value]="previewPct()"
          (input)="previewPct.set(value($event)); previewResult.set(null); previewError.set(null)"
        />
        <button type="submit" data-testid="rate-preview">Preview</button>
        @if (previewResult(); as result) {
          <p>
            Claims changed: <span data-testid="rate-preview-count">{{ result.count | fig }}</span>;
            difference: <span data-testid="rate-preview-diff">{{ result.diff | fig }}</span>
          </p>
        }
        @if (previewError(); as message) {
          <p role="alert" data-testid="rate-preview-error">{{ message | fig }}</p>
        }
      </form>
      <form aria-label="Import rates" (submit)="importRates($event)">
        <label for="import-rates">Import rates, one per line: grade,rate,from,to (from and to may be left out)</label>
        <textarea
          id="import-rates"
          data-testid="import-rates"
          rows="4"
          [value]="importText()"
          (input)="importText.set(value($event)); importError.set(null)"
        ></textarea>
        <button type="submit" data-testid="import-rates-submit">Import rates</button>
        @if (importSet() !== null) {
          Set <strong data-testid="import-rates-set">{{ importSet() | fig }}</strong>
        }
        @if (importError(); as message) {
          <p role="alert" data-testid="import-rates-error">{{ message | fig }}</p>
        }
      </form>
      <table>
        <caption>In force today</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th><th scope="col">Versions</th></tr>
        </thead>
        <tbody>
          @for (g of grades(); track g) {
            <tr>
              <th scope="row">{{ g | fig }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ current(g) ?? 'n/a' | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>All rates</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Rate (%)</th><th scope="col">Versions</th></tr>
        </thead>
        <tbody>
          @for (r of ownRates(); track r.grade + '-' + r.from) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade | fig }}</th>
              <td data-testid="rate-from">{{ r.from ?? 'always' | fig }}</td>
              <td data-testid="rate-to">{{ r.to ?? 'no end' | fig }}</td>
              <td data-testid="rate-pct">{{ r.pct | fig }}</td>
              <td data-testid="rate-version">{{ r.version | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Rates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly grades = input.required<string[]>();
  readonly rates = input.required<Rate[]>();
  /** The sheet's own rates, leaving out the parent sheets' it falls back on. */
  protected readonly ownRates = computed(() => this.rates().filter((r) => r.level === 0));
  /** A rate was set. */
  readonly saved = output<Rate>();
  /** Rates were imported: the screen is to re-read them. */
  readonly imported = output<void>();

  protected readonly grade = signal('A');
  protected readonly pct = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly previewGrade = signal('A');
  protected readonly previewPct = signal('');
  protected readonly previewResult = signal<RatePreview | null>(null);
  protected readonly previewError = signal<string | null>(null);
  /** A preview speaks of the rates and sheet it was worked out on: it goes once they change. */
  private readonly previewStale = effect(() => {
    this.rates();
    this.session.sheet();
    this.previewResult.set(null);
  });
  /** The rate import box: its lines, how many its last import set (null while it runs and before the first). */
  protected readonly importText = signal('');
  protected readonly importSet = signal<number | null>(null);
  protected readonly importError = signal<string | null>(null);
  /** The import box speaks of the sheet it was used on: it is cleared once another is loaded. */
  private readonly importStale = effect(() => {
    this.session.sheet();
    this.importText.set('');
    this.importSet.set(null);
    this.importError.set(null);
  });
  private importing = false;
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  /** The grade's rate in force on the sheet's today, or undefined when none is. */
  protected current(grade: string): string | undefined {
    const today = this.session.today();
    return today === null ? undefined : rateOn(this.rates(), grade, today);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const pct = this.pct().trim();
    if (!/^\d{1,5}(\.\d{1,2})?$/.test(pct)) {
      this.error.set('Type a rate from 0.00 to 99999.99 percent, with at most two decimals.');
      return;
    }
    const from = this.from().trim();
    if (from !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.error.set('Pick a from-date for the rate, or leave it blank for always.');
      return;
    }
    const to = this.to().trim();
    if (to !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      this.error.set('Pick a to-date for the rate, or leave it blank for no end.');
      return;
    }
    if (to !== '' && from !== '' && to < from) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    const grade = this.grade();
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<Rate>(
          `/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}`,
          { pct, from: from === '' ? null : from, to: to === '' ? null : to },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) {
        this.pct.set('');
        this.from.set('');
        this.to.set('');
        this.saved.emit(saved);
      }
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'Another user set this rate at the same moment; try again.'
          : 'The rate could not be set.',
      );
    } finally {
      this.submitting = false;
    }
  }

  /** Shows what the candidate rate would change for the grade; nothing is saved. */
  protected async preview(event: Event): Promise<void> {
    event.preventDefault();
    this.previewResult.set(null);
    this.previewError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const pct = this.previewPct().trim();
    if (!/^\d{1,5}(\.\d{1,2})?$/.test(pct)) {
      this.previewError.set('Type a candidate rate from 0.00 to 99999.99 percent, with at most two decimals.');
      return;
    }
    try {
      const result = await firstValueFrom(
        this.http.get<RatePreview>(`/api/sheets/${encodeURIComponent(sheet)}/rates/preview`, {
          ...this.session.headers(),
          params: { grade: this.previewGrade(), pct },
        }),
      );
      if (this.session.sheet() === sheet) this.previewResult.set(result);
    } catch {
      this.previewError.set('The preview could not be worked out.');
    }
  }

  /**
   * Set a rate per pasted line of grade,rate,from,to, in order, each as the rate form would. Refused as a whole when
   * any line would be.
   */
  protected async importRates(event: Event): Promise<void> {
    event.preventDefault();
    if (this.importing) return;
    this.importError.set(null);
    this.importSet.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = this.importText();
    if (!text.trim()) {
      this.importError.set('Paste at least one line of grade,rate,from,to.');
      return;
    }
    this.importing = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ set: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/rates/import`,
          { text },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.importText.set('');
      this.importSet.set(result.set);
      this.imported.emit();
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const detail = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' ? ` ${e.error.message}` : '';
      this.importError.set('The rates could not be imported; none were set.' + detail);
    } finally {
      this.importing = false;
    }
  }
}
