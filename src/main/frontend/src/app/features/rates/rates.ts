import { Component, inject, signal } from '@angular/core';
import { GRADES } from '../../core/people';
import { NO_RATE, Rates } from '../../core/rates';

// The sheet's rate for each grade: one form sets a grade's rate, and the table shows each grade's
// current rate, or n/a when it has none.
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
        <button type="submit" data-testid="rate-form-submit" [disabled]="saving()">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err }}</p>
        }
      </form>
      <table data-testid="rate-list">
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="rates.current(g) === noRate ? null : 'rate-row-' + g + '-start'">
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">
                @if (rates.current(g) !== noRate) {
                  <span data-testid="rate-pct">{{ rates.current(g) }}</span>
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
export class RatesPanel {
  protected readonly rates = inject(Rates);
  protected readonly grades = GRADES;
  protected readonly noRate = NO_RATE;
  protected readonly grade = signal<string>('A');
  protected readonly pct = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.rates.set(this.grade(), this.pct());
      if (err) this.error.set(err);
      else this.pct.set('');
    } finally {
      this.saving.set(false);
    }
  }
}
