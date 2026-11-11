import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Rate {
  grade: string;
  /** A percent with two decimals, e.g. "12.50". */
  pct: string;
  /** The first day the rate applies (yyyy-mm-dd); null when it applies from the beginning of time. */
  from: string | null;
}

/**
 * The percent of the grade's rate in force on the date: the one with the latest from-date on or
 * before it, an undated rate counting as from the beginning of time; undefined when the date is
 * before every rate.
 */
export function rateOn(rates: Rate[], grade: string, date: string): string | undefined {
  let found: Rate | undefined;
  for (const r of rates) {
    if (r.grade !== grade || (r.from !== null && r.from > date)) continue;
    if (found === undefined || (found.from ?? '') < (r.from ?? '')) found = r;
  }
  return found?.pct;
}

/** Hundredths of a unit in a decimal with at most two decimals ("10.25" is 1025n). */
function hundredths(value: string): bigint {
  const [whole, fraction = ''] = value.trim().split('.');
  return BigInt(whole || '0') * 100n + BigInt((fraction + '00').slice(0, 2));
}

/** An amount times a percent, divided by 100 and rounded half to even to two decimals; n/a with no rate. */
export function dueOf(amount: string, pct: string | undefined): string {
  if (pct === undefined) return 'n/a';
  // cents times hundredths of a percent are millionths of a unit: round them to cents, a half to the even cent.
  const millionths = hundredths(amount) * hundredths(pct);
  const rest = millionths % 10000n;
  let cents = millionths / 10000n;
  if (rest > 5000n || (rest === 5000n && cents % 2n === 1n)) cents += 1n;
  return `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`;
}

/** Adds dues with two decimals exactly, skipping the n/a ones. */
export function sumDues(dues: string[]): string {
  const cents = dues.filter((d) => d !== 'n/a').reduce((total, d) => total + hundredths(d), 0n);
  return `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`;
}

// The rate in force on the sheet's today for each grade, every rate with its from-date, and the form
// that sets a grade's rate from a date.
@Component({
  selector: 'app-rates',
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
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
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
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as message) {
          <p role="alert" data-testid="rate-form-error">{{ message }}</p>
        }
      </form>
      <table>
        <caption>In force today</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades(); track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ current(g) ?? 'n/a' }}</td>
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
          @for (r of rates(); track r.grade + '-' + r.from) {
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

  readonly grades = input.required<string[]>();
  readonly rates = input.required<Rate[]>();
  /** A rate was set. */
  readonly saved = output<Rate>();

  protected readonly grade = signal('A');
  protected readonly pct = signal('');
  protected readonly from = signal('');
  protected readonly error = signal<string | null>(null);
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
    if (!/^\d{1,3}(\.\d{1,2})?$/.test(pct)) {
      this.error.set('Type a rate from 0.00 to 999.99 percent, with at most two decimals.');
      return;
    }
    const from = this.from().trim();
    if (from !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.error.set('Pick a from-date for the rate, or leave it blank for always.');
      return;
    }
    const grade = this.grade();
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<Rate>(
          `/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}`,
          { pct, from: from === '' ? null : from },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) {
        this.pct.set('');
        this.from.set('');
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
}
