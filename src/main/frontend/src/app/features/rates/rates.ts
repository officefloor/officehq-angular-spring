import { HttpClient } from '@angular/common/http';
import { Component, Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface RateView {
  grade: string;
  pct: string;
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
 * rounded half up to two decimals; null (n/a) when there is no rate. Worked in whole numbers so
 * nothing drifts.
 */
export function dueOn(amount: string, pct: string | undefined): string | null {
  if (pct === undefined) return null;
  const due = (cents(amount) * cents(pct) + 5000n) / 10000n;
  return formatCents(due);
}

/** The sum of two-decimal values, skipping n/a. */
export function sumSkippingNa(values: readonly (string | null)[]): string {
  return formatCents(values.reduce((sum, v) => (v === null ? sum : sum + cents(v)), 0n));
}

function formatCents(c: bigint): string {
  return `${c / 100n}.${(c % 100n).toString().padStart(2, '0')}`;
}

// The rates set on the active sheet, by grade; a grade with no rate is not in the map.
@Injectable({ providedIn: 'root' })
export class RateStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly rates = signal<ReadonlyMap<string, string>>(new Map());

  /** Re-read the sheet's rates from the store. */
  async load(sheet: string): Promise<void> {
    this.rates.set(new Map());
    const list = await firstValueFrom(this.http.get<RateView[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates`));
    if (this.session.sheetId() === sheet) this.rates.set(new Map(list.map((r) => [r.grade, r.pct])));
  }

  async set(sheet: string, grade: string, pct: string): Promise<void> {
    const r = await firstValueFrom(
      this.http.put<RateView>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}`, { pct }),
    );
    if (this.session.sheetId() === sheet) this.rates.update((m) => new Map(m).set(r.grade, r.pct));
  }
}

// The rate for each grade on the sheet, and the form that sets one.
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
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err }}</p>
        }
      </form>

      <table>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="store.rates().has(g) ? 'rate-row-' + g + '-start' : null">
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">
                @if (store.rates().get(g); as pct) {
                  <span data-testid="rate-pct">{{ pct }}</span>
                } @else {
                  n/a
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Rates {
  private readonly session = inject(Session);
  protected readonly store = inject(RateStore);

  protected readonly grades = GRADES;
  protected readonly grade = signal('A');
  protected readonly pct = signal('');
  protected readonly error = signal<string | null>(null);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
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
    try {
      await this.store.set(sheet, this.grade(), pct);
    } catch {
      this.error.set('Could not set that rate.');
    }
  }
}
