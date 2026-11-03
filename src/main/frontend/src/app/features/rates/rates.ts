import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, model, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, cents, money } from '../claims/claims';

// The sheet's rates for each grade (a percent with two decimals, each from an optional from-date)
// and the form that sets one. Each grade shows the rate in force on the sheet's today, or n/a when
// none is. What is due on an amount is worked out here from those rates.
export interface Rate {
  grade: string;
  /** The date the rate is in force from; null for a rate in force from the beginning of time. */
  from: string | null;
  pct: string;
}

const GRADES = ['A', 'B', 'C'] as const;

/** A percent: 0.00 to 999.99, with up to two decimals. */
const PCT = /^\d{1,3}(\.\d{1,2})?$/;

/** A date as the date input gives it: yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The grade's rate in force on a date (yyyy-mm-dd): the one with the latest from-date on or before
 * it, an undated rate counting as from the beginning of time. Undefined when none applies yet.
 */
export function pctOn(rates: readonly Rate[], grade: string, date: string | null): string | undefined {
  let best: Rate | undefined;
  for (const r of rates) {
    if (r.grade !== grade || (r.from !== null && (date === null || r.from > date))) continue;
    if (best === undefined || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  return best?.pct;
}

/**
 * What is due on an amount in whole cents at a percent: cents times the rate divided by 100,
 * rounded half to even to whole cents (amounts are never negative). Null when there is no rate.
 */
export function dueCents(amountCents: number, pct: string | undefined): number | null {
  if (pct === undefined) return null;
  // cents x rate-in-hundredths-of-a-percent is 10000 times the due in cents
  const scaled = BigInt(amountCents) * BigInt(cents(pct));
  const whole = scaled / 10000n;
  const rest = scaled % 10000n;
  return Number(rest > 5000n || (rest === 5000n && whole % 2n === 1n) ? whole + 1n : whole);
}

/**
 * A claim's figure in whole cents: a settled claim's due fixed when it was settled, any other
 * claim's live due at the rate for the grade in force on the claim's date. Null when it has none.
 */
export function claimDueCents(c: Claim, rates: readonly Rate[], grade: string): number | null {
  return c.settledDue !== null ? cents(c.settledDue) : dueCents(cents(c.amount), pctOn(rates, grade, c.date));
}

/** A due in whole cents with two decimals, or n/a when there is no rate. */
export function dueText(c: number | null): string {
  return c === null ? 'n/a' : money(c);
}

@Component({
  selector: 'app-rates',
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form aria-label="Rate" (submit)="save($event)">
        <label for="rate-form-grade">Grade</label>
        <select
          id="rate-form-grade"
          data-testid="rate-form-grade"
          [value]="gradeInput()"
          (change)="gradeInput.set($any($event.target).value); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === gradeInput()">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input
          id="rate-form-pct"
          data-testid="rate-form-pct"
          inputmode="decimal"
          [value]="pctInput()"
          (input)="pctInput.set($any($event.target).value); error.set(null)"
        />
        <label for="rate-form-from">From (blank: always)</label>
        <input
          id="rate-form-from"
          type="date"
          data-testid="rate-form-from"
          [value]="fromInput()"
          (input)="fromInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err }}</p>
        }
      </form>
      <table aria-label="Rates in force today">
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate today (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ current().get(g) ?? 'n/a' }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table data-testid="rates-list" aria-label="All rates">
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">From</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (r of sorted(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? 'always' }}</td>
              <td data-testid="rate-pct">{{ r.pct }}</td>
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

  /** The sheet's rates, shared with whoever works out what is due. */
  readonly rates = model<Rate[]>([]);

  protected readonly grades = GRADES;
  /** Each grade's rates, the undated one first and then by from-date. */
  protected readonly sorted = computed(() =>
    [...this.rates()].sort(
      (a, b) => a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? ''),
    ),
  );
  /** Each grade's rate in force on the sheet's today. */
  protected readonly current = computed(() => {
    const today = this.session.today();
    return new Map(GRADES.map((g) => [g, pctOn(this.rates(), g, today)]));
  });
  protected readonly gradeInput = signal<string>('A');
  protected readonly pctInput = signal('');
  protected readonly fromInput = signal('');
  protected readonly error = signal<string | null>(null);

  constructor() {
    // Re-read the sheet's rates from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.rates.set([]);
      this.gradeInput.set('A');
      this.pctInput.set('');
      this.fromInput.set('');
      this.error.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.post<Rate[]>('/api/rates/list', { sheet }));
      if (this.session.sheet() === sheet) this.rates.set(list);
    } catch {
      this.error.set('Could not read the rates on this sheet.');
    }
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheet();
    const grade = this.gradeInput();
    const pct = this.pctInput().trim();
    const from = this.fromInput().trim() || null;
    if (!GRADES.includes(grade as (typeof GRADES)[number])) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    if (!PCT.test(pct)) {
      this.error.set('Enter a rate from 0.00 to 999.99, with up to two decimals.');
      return;
    }
    if (from !== null && (!DATE.test(from) || Number.isNaN(Date.parse(from)))) {
      this.error.set('Enter a from-date as yyyy-mm-dd, or leave it blank for always.');
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.post<Rate>('/api/rates/set', { sheet, user: this.session.user(), grade, pct, from }),
      );
      if (this.session.sheet() !== sheet) return;
      this.rates.update((list) => [
        ...list.filter((r) => r.grade !== saved.grade || r.from !== saved.from),
        saved,
      ]);
      this.pctInput.set('');
      this.fromInput.set('');
    } catch {
      this.error.set('Could not set this rate.');
    }
  }
}
