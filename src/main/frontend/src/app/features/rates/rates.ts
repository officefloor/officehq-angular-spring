import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Rate {
  grade: string;
  /** The first date (yyyy-mm-dd) it applies on; null when it applies from the beginning of time. */
  from: string | null;
  /** A percent, always two decimals, e.g. "12.50". */
  pct: string;
}

export const GRADES = ['A', 'B', 'C'];

/** Shown for a due that has no rate. */
export const NA = 'n/a';

/** Cents times a percent (two decimals), divided by 100, rounded half to even (or half up) to whole cents. */
export function dueCents(amountCents: number, pct: string, halfUp = false): number {
  const [whole, frac = ''] = pct.split('.');
  const pctHundredths = BigInt(whole) * 100n + BigInt(frac.padEnd(2, '0'));
  // Rounded on the size of the product, so a negative due rounds as its positive would.
  const sign = amountCents < 0 ? -1 : 1;
  const product = BigInt(Math.abs(amountCents)) * pctHundredths;
  const quotient = product / 10000n;
  const remainder = product % 10000n;
  const up = remainder > 5000n || (remainder === 5000n && (halfUp || quotient % 2n === 1n));
  return sign * Number(up ? quotient + 1n : quotient);
}

/** Of one grade's rates, the percent in force on a date (the latest from-date on or before it), or null. */
export function pctOn(rates: Rate[], date: string): string | null {
  // No from-date sorts as '', before every date.
  let best: Rate | null = null;
  for (const r of rates) {
    const from = r.from ?? '';
    if (from <= date && (best === null || from > (best.from ?? ''))) best = r;
  }
  return best?.pct ?? null;
}

// The sheet's rates, each for a grade from a from-date (or from the start), with one form to set
// a grade's rate from a date; and per grade, the rate in force on the sheet's today.
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
        <label for="rate-form-from">From (blank: from the start)</label>
        <input id="rate-form-from" type="date" data-testid="rate-form-from" formControlName="from" />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="rate-form-error">{{ error() }}</p>
        }
      </form>
      <table>
        <caption>In force today</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <td>{{ g }}</td>
              <td [attr.data-testid]="'rate-current-' + g">{{ currentOf(g) ?? na }}</td>
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
          @for (r of sorted(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <td>{{ r.grade }}</td>
              <td data-testid="rate-from">{{ r.from ?? 'start' }}</td>
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

  readonly rates = input.required<Rate[]>();
  /** The sheet's today, for the rate in force on it. */
  readonly today = input.required<string | null>();
  /** A rate set, as the store now holds it. */
  readonly saved = output<Rate>();

  protected readonly grades = GRADES;
  protected readonly na = NA;
  protected readonly error = signal('');
  /** A submit is under way; another one waits for its answer rather than storing twice. */
  private saving = false;
  protected readonly form = new FormGroup({
    grade: new FormControl('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
  });
  /** By grade, then from-date, the one from the start first. */
  protected readonly sorted = computed(() =>
    [...this.rates()].sort((a, b) =>
      a.grade !== b.grade ? (a.grade < b.grade ? -1 : 1) : (a.from ?? '') < (b.from ?? '') ? -1 : 1,
    ),
  );

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected currentOf(grade: string): string | null {
    const today = this.today();
    return today === null ? null : pctOn(this.rates().filter((r) => r.grade === grade), today);
  }

  protected async save(): Promise<void> {
    if (this.saving) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.form.getRawValue();
    const pct = raw.pct.trim();
    const from = raw.from.trim();
    if (!GRADES.includes(raw.grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    if (!/^\d{1,5}(\.\d{1,2})?$/.test(pct)) {
      this.error.set('Enter a percent of 0.00 or more, with at most two decimals.');
      return;
    }
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.error.set('Enter a from-date, or leave it blank for from the start.');
      return;
    }
    this.saving = true;
    try {
      const rate = await firstValueFrom(
        this.http.put<Rate>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${raw.grade}`, { pct, from: from || null }, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(rate);
    } catch {
      this.error.set('Could not set that rate.');
    } finally {
      this.saving = false;
    }
  }
}
