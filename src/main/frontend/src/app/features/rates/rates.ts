import { HttpClient } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { money } from '../claims/claims';

export interface Rate {
  grade: string;
  /** A percent, always two decimals, e.g. "12.50". */
  pct: string;
}

export const GRADES = ['A', 'B', 'C'];

/** Shown for a due that has no rate. */
export const NA = 'n/a';

/** Cents times a percent (two decimals), divided by 100, rounded half up to whole cents. */
export function dueCents(amountCents: number, pct: string): number {
  const [whole, frac = ''] = pct.split('.');
  const pctHundredths = BigInt(whole) * 100n + BigInt(frac.padEnd(2, '0'));
  return Number((BigInt(amountCents) * pctHundredths + 5000n) / 10000n);
}

/** The due on an amount in cents at a grade's percent, or n/a when the grade has no rate. */
export function due(amountCents: number, pct: string | null): string {
  return pct === null ? NA : money(dueCents(amountCents, pct));
}

// The sheet's rate for each grade, with one form to set a grade's rate.
@Component({
  selector: 'app-rates',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form [formGroup]="form" (ngSubmit)="save()" aria-label="Set rate">
        <label for="rate-form-grade">Grade</label>
        <select id="rate-form-grade" data-testid="rate-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input id="rate-form-pct" data-testid="rate-form-pct" formControlName="pct" inputmode="decimal" />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="rate-form-error">{{ error() }}</p>
        }
      </form>
      <table>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="'rate-row-' + g + '-start'">
              <td>{{ g }}</td>
              <td [attr.data-testid]="'rate-current-' + g"><span data-testid="rate-pct">{{ pctOf(g) ?? na }}</span></td>
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

  readonly rates = input.required<Rate[]>();
  /** A rate set, as the store now holds it. */
  readonly saved = output<Rate>();

  protected readonly grades = GRADES;
  protected readonly na = NA;
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    grade: new FormControl('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected pctOf(grade: string): string | null {
    return this.rates().find((r) => r.grade === grade)?.pct ?? null;
  }

  protected async save(): Promise<void> {
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.form.getRawValue();
    const pct = raw.pct.trim();
    if (!GRADES.includes(raw.grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    if (!/^\d{1,5}(\.\d{1,2})?$/.test(pct)) {
      this.error.set('Enter a percent of 0.00 or more, with at most two decimals.');
      return;
    }
    try {
      const rate = await firstValueFrom(
        this.http.put<Rate>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${raw.grade}`, { pct }, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(rate);
    } catch {
      this.error.set('Could not set that rate.');
    }
  }
}
