import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { GRADES, Grade } from '../../core/people';
import { RATE, Rates } from '../../core/rates';
import { Session } from '../../core/session';

// The sheet's rates for each grade, per from-date, the rate in force on today, and the form that sets one.
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
        <label for="rate-from">From (optional)</label>
        <input
          id="rate-from"
          type="date"
          data-testid="rate-form-from"
          [value]="from()"
          (input)="from.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err }}</p>
        }
      </form>

      <table>
        <caption>Rate in force on today</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <td>{{ g }}</td>
              <td [attr.data-testid]="'rate-current-' + g">{{ rates.byGrade().get(g) ?? 'n/a' }}</td>
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
              <td data-testid="rate-pct">{{ r.rate }}</td>
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
  protected readonly from = signal('');
  /** The rates by grade, then from-date, a rate with no from-date first. */
  protected readonly sorted = computed(() =>
    [...this.rates.all()].sort((a, b) =>
      a.grade !== b.grade ? a.grade.localeCompare(b.grade) : (a.from ?? '').localeCompare(b.from ?? ''),
    ),
  );
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
    const from = this.from().trim();
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.error.set('Enter a from-date as yyyy-mm-dd, or leave it empty.');
      return;
    }
    try {
      await this.rates.set(this.grade(), pct, from || null);
      this.pct.set('');
      this.from.set('');
    } catch {
      this.error.set('The rate could not be set.');
    }
  }
}
