import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Rate {
  grade: string;
  /** A percent with two decimals, e.g. "12.50". */
  pct: string;
}

/** Hundredths of a unit in a decimal with at most two decimals ("10.25" is 1025n). */
function hundredths(value: string): bigint {
  const [whole, fraction = ''] = value.trim().split('.');
  return BigInt(whole || '0') * 100n + BigInt((fraction + '00').slice(0, 2));
}

/** An amount times a percent, divided by 100 and rounded half up to two decimals; n/a with no rate. */
export function dueOf(amount: string, pct: string | undefined): string {
  if (pct === undefined) return 'n/a';
  // cents times hundredths of a percent are millionths of a unit: round them to cents.
  const cents = (hundredths(amount) * hundredths(pct) + 5000n) / 10000n;
  return `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`;
}

/** Adds dues with two decimals exactly, skipping the n/a ones. */
export function sumDues(dues: string[]): string {
  const cents = dues.filter((d) => d !== 'n/a').reduce((total, d) => total + hundredths(d), 0n);
  return `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`;
}

// The rate for each grade on the active sheet, and the form that sets a grade's rate.
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
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as message) {
          <p role="alert" data-testid="rate-form-error">{{ message }}</p>
        }
      </form>
      <table>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades(); track g) {
            @let rate = rateOf(g);
            <tr [attr.data-testid]="rate === undefined ? null : 'rate-row-' + g + '-start'">
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="rate === undefined ? null : 'rate-pct'">
                <span [attr.data-testid]="'rate-current-' + g">{{ rate ?? 'n/a' }}</span>
              </td>
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
  protected readonly error = signal<string | null>(null);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected rateOf(grade: string): string | undefined {
    return this.rates().find((r) => r.grade === grade)?.pct;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const pct = this.pct().trim();
    if (!/^\d{1,3}(\.\d{1,2})?$/.test(pct)) {
      this.error.set('Type a rate from 0.00 to 999.99 percent, with at most two decimals.');
      return;
    }
    const grade = this.grade();
    try {
      const saved = await firstValueFrom(
        this.http.put<Rate>(
          `/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}`,
          { pct },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) {
        this.pct.set('');
        this.saved.emit(saved);
      }
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'Another user set this rate at the same moment; try again.'
          : 'The rate could not be set.',
      );
    }
  }
}
