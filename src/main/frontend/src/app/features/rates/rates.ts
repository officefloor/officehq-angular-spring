import { HttpClient } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Rate {
  grade: string;
  /** A percent with two decimals, e.g. "12.50". */
  rate: string;
}

export const GRADES = ['A', 'B', 'C'];

/** Shown for a due that has no rate to work from. */
export const NO_RATE = 'n/a';

/** The due on an amount in cents at a percent rate (two decimals), in cents: amount times rate
 *  divided by 100, rounded half up. Worked in whole numbers so nothing is lost to floating point. */
export function dueCents(amountCents: number, rate: string): number {
  const hundredths = BigInt(Math.round(Number(rate) * 100));
  return Number((BigInt(amountCents) * hundredths + 5000n) / 10000n);
}

const PERCENT = /^\d{1,5}(\.\d{1,2})?$/;

// The active sheet's rate for each grade, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class RateStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly rates = signal<Rate[]>([]);
  /** Each grade's rate by grade; a grade with no rate is absent. */
  readonly byGrade = computed(() => new Map(this.rates().map((r) => [r.grade, r.rate])));

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.rates.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Set a grade's rate on the active sheet. */
  async set(grade: string, rate: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<Rate>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}`, { rate }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.rates.update((list) => [...list.filter((r) => r.grade !== saved.grade), saved]);
  }

  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(
      this.http.get<Rate[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates`),
    );
    if (this.session.loads() !== load) return;
    this.rates.set(list);
  }
}

// The sheet's rate for each grade, and the form that sets one.
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
          @for (g of grades; track g) {
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
      </form>
      @if (error(); as message) {
        <p role="alert" data-testid="rate-form-error">{{ message }}</p>
      }
      <table>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="store.byGrade().has(g) ? 'rate-row-' + g + '-start' : null">
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">
                @if (store.byGrade().get(g); as rate) {
                  <span data-testid="rate-pct">{{ rate }}</span>
                } @else {
                  {{ noRate }}
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
  protected readonly store = inject(RateStore);
  protected readonly grades = GRADES;
  protected readonly noRate = NO_RATE;
  protected readonly grade = signal('A');
  protected readonly pct = signal('');
  protected readonly error = signal<string | null>(null);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const pct = this.pct().trim();
    if (!PERCENT.test(pct)) {
      this.error.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    try {
      await this.store.set(this.grade(), pct);
    } catch {
      this.error.set('The rate could not be set.');
    }
  }
}
