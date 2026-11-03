import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, model, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { cents, money } from '../claims/claims';

// The sheet's rate for each grade (a percent with two decimals) and the form that sets one. A
// grade with no rate shows n/a. What is due on an amount is worked out here from those rates.
export interface Rate {
  grade: string;
  pct: string;
}

const GRADES = ['A', 'B', 'C'] as const;

/** A percent: 0.00 to 999.99, with up to two decimals. */
const PCT = /^\d{1,3}(\.\d{1,2})?$/;

/**
 * What is due on an amount in whole cents at a percent: cents times the rate divided by 100,
 * rounded half up to whole cents (amounts are never negative). Null when there is no rate.
 */
export function dueCents(amountCents: number, pct: string | undefined): number | null {
  if (pct === undefined) return null;
  // cents x rate-in-hundredths-of-a-percent is 10000 times the due in cents
  const scaled = BigInt(amountCents) * BigInt(cents(pct));
  return Number((scaled + 5000n) / 10000n);
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
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err }}</p>
        }
      </form>
      <table data-testid="rates-list">
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="byGrade().has(g) ? 'rate-row-' + g + '-start' : null">
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">
                <span [attr.data-testid]="byGrade().has(g) ? 'rate-pct' : null">{{ byGrade().get(g) ?? 'n/a' }}</span>
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

  /** The sheet's rates, shared with whoever works out what is due. */
  readonly rates = model<Rate[]>([]);

  protected readonly grades = GRADES;
  protected readonly byGrade = computed(() => new Map(this.rates().map((r) => [r.grade, r.pct])));
  protected readonly gradeInput = signal<string>('A');
  protected readonly pctInput = signal('');
  protected readonly error = signal<string | null>(null);

  constructor() {
    // Re-read the sheet's rates from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.rates.set([]);
      this.gradeInput.set('A');
      this.pctInput.set('');
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
    if (!GRADES.includes(grade as (typeof GRADES)[number])) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    if (!PCT.test(pct)) {
      this.error.set('Enter a rate from 0.00 to 999.99, with up to two decimals.');
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.post<Rate>('/api/rates/set', { sheet, user: this.session.user(), grade, pct }),
      );
      if (this.session.sheet() !== sheet) return;
      this.rates.update((list) => [...list.filter((r) => r.grade !== saved.grade), saved]);
      this.pctInput.set('');
    } catch {
      this.error.set('Could not set this rate.');
    }
  }
}
