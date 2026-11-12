import { Component, inject, signal } from '@angular/core';
import { GRADES } from '../../core/people';
import { Rates } from '../../core/rates';

// The sheet's rates for each grade: one form sets a grade's rate from a date (or from the beginning
// of time), one table shows each grade's rate in force on the sheet's today, or n/a when none
// applies, and another lists every rate with its from-date.
@Component({
  selector: 'app-rates',
  template: `
    <section aria-label="Rates" data-testid="rates">
      <h2>Rates</h2>
      <form (submit)="submit($event)" data-testid="rate-form">
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
        <label for="rate-form-from">From (YYYY-MM-DD, blank for always)</label>
        <input
          id="rate-form-from"
          data-testid="rate-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="rate-form-submit" [disabled]="saving()">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err }}</p>
        }
      </form>
      <table data-testid="rate-today">
        <caption>In force today</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ rates.current(g) }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table data-testid="rate-list">
        <caption>All rates</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">From</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (r of rates.rates(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? 'always' }}</td>
              <td><span data-testid="rate-pct">{{ r.rate }}</span></td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class RatesPanel {
  protected readonly rates = inject(Rates);
  protected readonly grades = GRADES;
  protected readonly grade = signal<string>('A');
  protected readonly pct = signal('');
  protected readonly from = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.rates.set(this.grade(), this.pct(), this.from());
      if (err) this.error.set(err);
      else {
        this.pct.set('');
        this.from.set('');
      }
    } finally {
      this.saving.set(false);
    }
  }
}
