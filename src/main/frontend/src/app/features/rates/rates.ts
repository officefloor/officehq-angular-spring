import { HttpClient } from '@angular/common/http';
import { Component, Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface RateView {
  grade: string;
  pct: string;
  // The date the rate applies from; null for a rate that applies from the beginning of time.
  from: string | null;
}

export const GRADES = ['A', 'B', 'C'];

const PCT = /^\d{1,5}(\.\d{1,2})?$/;

/** Whole cents of a two-decimal value. */
function cents(value: string): bigint {
  const [whole, frac = ''] = value.split('.');
  return BigInt(whole) * 100n + BigInt((frac + '00').slice(0, 2));
}

/**
 * The due on an amount at a rate (both two-decimal strings): amount times rate divided by 100,
 * rounded half to even to two decimals; null (n/a) when there is no rate. Worked in whole numbers
 * so nothing drifts.
 */
export function dueOn(amount: string, pct: string | undefined): string | null {
  if (pct === undefined) return null;
  const product = cents(amount) * cents(pct);
  let due = product / 10000n;
  const rest = product % 10000n;
  if (rest > 5000n || (rest === 5000n && due % 2n === 1n)) due += 1n;
  return formatCents(due);
}

/** The sum of two-decimal values, skipping n/a. */
export function sumSkippingNa(values: readonly (string | null)[]): string {
  return formatCents(values.reduce((sum, v) => (v === null ? sum : sum + cents(v)), 0n));
}

/**
 * The percent of the rate in force on the date among one grade's rates: the one with the latest
 * from-date on or before it, a rate with no from-date applying from the beginning of time;
 * undefined (n/a) when none applies yet.
 */
export function rateOn(rates: readonly RateView[], date: string | null): string | undefined {
  let best: RateView | undefined;
  for (const r of rates) {
    if (r.from !== null && (date === null || r.from > date)) continue;
    if (!best || (r.from !== null && (best.from === null || r.from > best.from))) best = r;
  }
  return best?.pct;
}

function formatCents(c: bigint): string {
  return `${c / 100n}.${(c % 100n).toString().padStart(2, '0')}`;
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
  readonly rates = computed(() => {
    const by = new Map<string, RateView[]>();
    for (const r of this.list()) by.set(r.grade, [...(by.get(r.grade) ?? []), r]);
    return by as ReadonlyMap<string, readonly RateView[]>;
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

  /** Re-read the sheet's rates from the store. */
  async load(sheet: string): Promise<void> {
    this.list.set([]);
    const list = await firstValueFrom(this.http.get<RateView[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates`));
    if (this.session.sheetId() === sheet) this.list.set([...list].sort(byGradeThenFrom));
  }

  /** Set the grade's rate from the date (null: from the beginning of time), replacing the one it had for it. */
  async set(sheet: string, grade: string, pct: string, from: string | null): Promise<void> {
    const r = await firstValueFrom(
      this.http.put<RateView>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}`, { pct, from }),
    );
    if (this.session.sheetId() === sheet) {
      this.list.update((l) => [...l.filter((x) => x.grade !== r.grade || x.from !== r.from), r].sort(byGradeThenFrom));
    }
  }
}

// The rate in force for each grade on the sheet's today, every rate set, and the form that sets one.
@Component({
  selector: 'app-rates',
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
          @for (g of grades; track g) {
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
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err }}</p>
        }
      </form>

      <table>
        <caption>In force on {{ session.sheetToday() }}</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ store.current().get(g) ?? 'n/a' }}</td>
            </tr>
          }
        </tbody>
      </table>

      <table>
        <caption>All rates</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">From</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (r of store.list(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? 'the start' }}</td>
              <td data-testid="rate-pct">{{ r.pct }}</td>
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

  protected readonly grades = GRADES;
  protected readonly grade = signal('A');
  protected readonly pct = signal('');
  protected readonly from = signal('');
  protected readonly error = signal<string | null>(null);
  // A submit in flight: a second submit before it answers is ignored, so it stores one rate.
  private submitting = false;

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
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
    if (!GRADES.includes(this.grade())) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    const from = this.from().trim();
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.error.set('Enter a from-date as yyyy-mm-dd.');
      return;
    }
    this.submitting = true;
    try {
      await this.store.set(sheet, this.grade(), pct, from || null);
    } catch {
      this.error.set('Could not set that rate.');
    } finally {
      this.submitting = false;
    }
  }
}
