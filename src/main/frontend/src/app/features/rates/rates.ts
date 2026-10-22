import { Component, computed, inject, input, output, signal } from '@angular/core';
import { GRADES, Grade } from '../../core/people';
import { FROM_DATE, NO_RATE, PERCENT, RateView, RatesApi, rateOn } from '../../core/rates';
import { Session } from '../../core/session';

// The sheet's rates: each grade's rate in force on the sheet's today, every rate by grade and
// from-date, and one form to set a grade's rate from a date (or from the beginning of time).
@Component({
  selector: 'app-rates',
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form (submit)="save($event)" aria-label="Set a rate">
        <label for="rate-form-grade">Grade</label>
        <select
          id="rate-form-grade"
          data-testid="rate-form-grade"
          [value]="grade()"
          (change)="grade.set(gradeOf($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
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
        <label for="rate-form-from">From (empty: from the start)</label>
        <input
          id="rate-form-from"
          type="date"
          data-testid="rate-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as message) {
          <p role="alert" data-testid="rate-form-error">{{ message }}</p>
        }
      </form>
      <table>
        <caption>In force on {{ today() }}</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ currentOf(g) ?? noRate }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>All rates</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">From</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (r of listed(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
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
  private readonly session = inject(Session);
  private readonly api = inject(RatesApi);

  /** The sheet's rates; a grade with no rate is not listed. */
  readonly rates = input.required<RateView[]>();
  /** The sheet's today, on which each grade's rate in force is shown. */
  readonly today = input.required<string>();
  /** A rate set through the form. */
  readonly saved = output<RateView>();

  protected readonly grades = GRADES;
  protected readonly noRate = NO_RATE;
  protected readonly grade = signal<Grade>('A');
  protected readonly pct = signal('');
  /** The from-date typed (yyyy-mm-dd); empty means from the beginning of time. */
  protected readonly from = signal('');
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  /** Every rate by grade, each grade's from the start, then by from-date. */
  protected readonly listed = computed(() =>
    [...this.rates()].sort((a, b) =>
      a.grade !== b.grade ? a.grade.localeCompare(b.grade) : (a.from ?? '').localeCompare(b.from ?? ''),
    ),
  );

  protected currentOf(grade: Grade): string | null {
    return rateOn(this.rates(), grade, this.today());
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected gradeOf(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const pct = this.pct().trim();
    if (!PERCENT.test(pct)) {
      this.error.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    const from = this.from().trim();
    if (from !== '' && !FROM_DATE.test(from)) {
      this.error.set('Type a from-date as yyyy-mm-dd, or leave it empty for from the start.');
      return;
    }
    this.busy = true;
    try {
      this.saved.emit(await this.api.set(sheet, this.grade(), { pct, from: from || null }));
      this.pct.set('');
      this.from.set('');
    } catch {
      this.error.set(`The rate for grade ${this.grade()} could not be set.`);
    } finally {
      this.busy = false;
    }
  }
}
