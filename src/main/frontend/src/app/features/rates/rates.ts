import { Component, inject, input, output, signal } from '@angular/core';
import { GRADES, Grade } from '../../core/people';
import { NO_RATE, PERCENT, RateView, RatesApi } from '../../core/rates';
import { Session } from '../../core/session';

// The sheet's rate for each grade: listed per grade and set through one form.
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
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as message) {
          <p role="alert" data-testid="rate-form-error">{{ message }}</p>
        }
      </form>
      <table>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <!-- A grade's rate applies from the start: its row is listed once the grade has one. -->
            <tr [attr.data-testid]="rateOf(g) === null ? null : 'rate-row-' + g + '-start'">
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">
                @if (rateOf(g); as pct) {
                  <span data-testid="rate-pct">{{ pct }}</span>
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
  private readonly session = inject(Session);
  private readonly api = inject(RatesApi);

  /** The sheet's rates; a grade with no rate is not listed. */
  readonly rates = input.required<RateView[]>();
  /** A rate set through the form. */
  readonly saved = output<RateView>();

  protected readonly grades = GRADES;
  protected readonly noRate = NO_RATE;
  protected readonly grade = signal<Grade>('A');
  protected readonly pct = signal('');
  protected readonly error = signal<string | null>(null);

  protected rateOf(grade: Grade): string | null {
    return this.rates().find((r) => r.grade === grade)?.pct ?? null;
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected gradeOf(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const pct = this.pct().trim();
    if (!PERCENT.test(pct)) {
      this.error.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    try {
      this.saved.emit(await this.api.set(sheet, this.grade(), pct));
      this.pct.set('');
    } catch {
      this.error.set(`The rate for grade ${this.grade()} could not be set.`);
    }
  }
}
