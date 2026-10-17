import { Component, effect, inject, signal, untracked } from '@angular/core';
import { GRADES, Grade } from '../../core/people';
import { RATE, Rates } from '../../core/rates';
import { Session } from '../../core/session';

// The sheet's rate for each grade, and the form that sets one.
@Component({
  selector: 'app-rates',
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>

      <form (submit)="save($event)">
        <label for="rate-grade">Grade</label>
        <select
          id="rate-grade"
          data-testid="rate-form-grade"
          [value]="grade()"
          (change)="grade.set($any($event.target).value); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="rate-pct">Rate (%)</label>
        <input
          id="rate-pct"
          inputmode="decimal"
          data-testid="rate-form-pct"
          [value]="pct()"
          (input)="pct.set($any($event.target).value); error.set(null)"
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
            <tr [attr.data-testid]="'rate-row-' + g + '-start'">
              <td>{{ g }}</td>
              <td [attr.data-testid]="'rate-current-' + g">
                @if (rates.byGrade().get(g); as r) {
                  <span data-testid="rate-pct">{{ r }}</span>
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
export class RatesPanel {
  protected readonly rates = inject(Rates);
  private readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly grade = signal<Grade>('A');
  protected readonly pct = signal('');
  protected readonly error = signal<string | null>(null);

  constructor() {
    // Re-read the store each time the sheet is (re-)loaded.
    effect(() => {
      this.session.loads();
      untracked(() => {
        this.error.set(null);
        void this.rates.reload();
      });
    });
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const pct = this.pct().trim();
    if (!RATE.test(pct)) {
      this.error.set('Enter a rate of 0.00 or more, below 1000, with at most two decimals.');
      return;
    }
    try {
      await this.rates.set(this.grade(), pct);
      this.pct.set('');
    } catch {
      this.error.set('The rate could not be set.');
    }
  }
}
