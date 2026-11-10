import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, model, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, cents, daysOverdue, dueBy, isCorrection, isOpen, money, plusDays } from '../claims/claims';

// The sheet's rates for each grade (a percent with two decimals, each from an optional from-date
// and through an optional to-date)
// and the form that sets one. Each grade shows the rate in force on the sheet's today, or n/a when
// none is. What is due on an amount is worked out here from those rates.
export interface Rate {
  grade: string;
  /** The date the rate is in force from; null for a rate in force from the beginning of time. */
  from: string | null;
  /** The last date the rate is in force; null for a rate in force until the grade's next one. */
  to: string | null;
  pct: string;
}

const GRADES = ['A', 'B', 'C'] as const;

/** A percent: 0.00 to 999.99, with up to two decimals. */
const PCT = /^\d{1,3}(\.\d{1,2})?$/;

/** A date as the date input gives it: yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** One of a person's grades, from a from-date (null: from the beginning of time). */
export interface DatedGrade {
  from: string | null;
  grade: string;
}

/**
 * The grade in force on a date (yyyy-mm-dd): the one with the latest from-date on or before it,
 * an undated grade counting as from the beginning of time. '' when none applies.
 */
export function gradeOn(grades: readonly DatedGrade[], date: string | null): string {
  let best: DatedGrade | undefined;
  for (const g of grades) {
    if (g.from !== null && (date === null || g.from > date)) continue;
    if (best === undefined || (best.from ?? '') < (g.from ?? '')) best = g;
  }
  return best?.grade ?? '';
}

/**
 * The grade's rate in force on a date (yyyy-mm-dd): the one with the latest from-date on or before
 * it, an undated rate counting as from the beginning of time. Undefined when none applies yet, or
 * when that one's to-date has passed by the date.
 */
export function pctOn(rates: readonly Rate[], grade: string, date: string | null): string | undefined {
  let best: Rate | undefined;
  for (const r of rates) {
    if (r.grade !== grade || (r.from !== null && (date === null || r.from > date))) continue;
    if (best === undefined || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  if (best?.to != null && (date === null || best.to < date)) return undefined;
  return best?.pct;
}

/**
 * What is due on an amount in whole cents at a percent: cents times the rate divided by 100,
 * rounded to whole cents half to even, or half up when `halfUp` (a negative amount rounds as its
 * opposite does, negated). Null when there is no rate.
 */
export function dueCents(amountCents: number, pct: string | undefined, halfUp = false): number | null {
  if (pct === undefined) return null;
  // cents x rate-in-hundredths-of-a-percent is 10000 times the due in cents
  const scaled = BigInt(Math.abs(amountCents)) * BigInt(cents(pct));
  const whole = scaled / 10000n;
  const rest = scaled % 10000n;
  const due = Number(rest > 5000n || (rest === 5000n && (halfUp || whole % 2n === 1n)) ? whole + 1n : whole);
  return amountCents < 0 ? -due : due;
}

/**
 * A claim's fixed figure in whole cents: the one bound by the person's last statement, else the
 * due fixed when it was settled. Null when it has none (its figure is live, or there was no rate).
 */
export function fixedDueCents(c: Claim): number | null {
  const fixed = c.stated && c.status !== 'PENDING' ? c.statedDue : c.settledDue;
  return fixed !== null ? cents(fixed) : null;
}

/**
 * Whether a claim's live figures round half up: it rounds by a date (see `roundingDate`) before
 * the sheet's policy date, the sheet's today when the policy date is blank. On or after it, they
 * round half to even.
 */
export function roundsHalfUp(roundOn: string, policyDate: string, today: string | null): boolean {
  const policy = policyDate !== '' ? policyDate : today;
  return policy !== null && roundOn < policy;
}

/**
 * A claim's figure in whole cents: a stated claim's figure bound by the last statement (not while
 * PENDING: its figure is live), a settled
 * claim's due fixed when it was settled, any other claim's live due at the rate for the grade in
 * force on the claim's date, rounded half up when `halfUp` (else half to even), raised to the
 * sheet's floor (in whole cents; a correction's is not). Null when it has none.
 */
export function claimDueCents(c: Claim, rates: readonly Rate[], grade: string, floor: number, halfUp = false): number | null {
  if (c.settledDue !== null) return cents(c.settledDue);
  if (c.stated && c.status !== 'PENDING') return fixedDueCents(c);
  const due = dueCents(cents(c.amount), pctOn(rates, grade, c.date), halfUp);
  return due === null || isCorrection(c) ? due : Math.max(due, floor);
}

/** One value recorded for a dated setting: in force from its from-date (blank: from the
 *  beginning of time) until the next. A blank value is none (no limit, no cap). */
export interface DatedValue {
  from: string;
  value: string;
}

/**
 * The value of a dated setting in force on a date (yyyy-mm-dd): the one with the latest from-date
 * on or before it, one from the beginning of time counting as the earliest. `start` before every
 * from-date.
 */
export function valueOn(values: readonly DatedValue[], date: string | null, start: string): string {
  let best: DatedValue | undefined;
  for (const v of values) {
    if (v.from !== '' && (date === null || v.from > date)) continue;
    if (best === undefined || best.from < v.from) best = v;
  }
  return best?.value ?? start;
}

/** The floor a sheet starts with: in force before its first recorded floor. */
export const START_FLOOR = '1.00';

/** The late percent a sheet starts with: in force before its first recorded late percent. */
export const START_LATE_PCT = '0.00';

/** What works out an overdue claim's late charge and rounding: the sheet's late percents (each
 *  claim's is the one in force on its date), today and policy date (blank: the sheet's today). */
export interface Lateness {
  latePcts: readonly DatedValue[];
  today: string | null;
  policyDate: string;
}

/**
 * A claim's due in whole cents as shown: its figure (see {@link claimDueCents}, raised to the floor
 * in force on the claim's date) with, when it is overdue, a late charge added on top: for each full
 * 30 days overdue, the late percent in force on that boundary date (its due-by plus 30, 60, 90 ...
 * days) of that figure, rounded to whole cents, the charges added up. Both round as the claim rounds by `roundOn` (see {@link roundsHalfUp}). The
 * charge is never bound by a statement; a settled claim's due already holds the charge it was
 * settled with. Null when it has no figure.
 */
export function chargedDueCents(
  c: Claim,
  rates: readonly Rate[],
  grade: string,
  floors: readonly DatedValue[],
  late: Lateness,
  roundOn: string,
): number | null {
  const halfUp = roundsHalfUp(roundOn, late.policyDate, late.today);
  const figure = claimDueCents(c, rates, grade, cents(valueOn(floors, c.date, START_FLOOR)), halfUp);
  if (figure === null || !isOpen(c) || isCorrection(c)) return figure;
  const periods = Math.floor(daysOverdue(c, late.today) / 30);
  let charge = 0;
  for (let k = 1; k <= periods; k++) {
    const boundary = plusDays(dueBy(c), 30 * k);
    charge += dueCents(figure, valueOn(late.latePcts, boundary, START_LATE_PCT), halfUp) ?? 0;
  }
  return figure + charge;
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
        <label for="rate-form-to">To (blank: until the next rate)</label>
        <input
          id="rate-form-to"
          type="date"
          data-testid="rate-form-to"
          [value]="toInput()"
          (input)="toInput.set($any($event.target).value); error.set(null)"
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
            <th scope="col">To</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (r of sorted(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? 'always' }}</td>
              <td data-testid="rate-to">{{ r.to ?? 'open' }}</td>
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
  protected readonly toInput = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's rates from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.rates.set([]);
      this.gradeInput.set('A');
      this.pctInput.set('');
      this.fromInput.set('');
      this.toInput.set('');
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
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const grade = this.gradeInput();
    const pct = this.pctInput().trim();
    const from = this.fromInput().trim() || null;
    const to = this.toInput().trim() || null;
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
    if (to !== null && (!DATE.test(to) || Number.isNaN(Date.parse(to)))) {
      this.error.set('Enter a to-date as yyyy-mm-dd, or leave it blank for until the next rate.');
      return;
    }
    if (to !== null && from !== null && to < from) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Rate>('/api/rates/set', { sheet, user: this.session.user(), grade, pct, from, to }),
      );
      if (this.session.sheet() !== sheet) return;
      this.rates.update((list) => [
        ...list.filter((r) => r.grade !== saved.grade || r.from !== saved.from),
        saved,
      ]);
      this.pctInput.set('');
      this.fromInput.set('');
      this.toInput.set('');
    } catch {
      this.error.set('Could not set this rate.');
    } finally {
      this.submitting = false;
    }
  }
}
