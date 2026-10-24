import { Component, inject, input, output, signal } from '@angular/core';
import { Session } from '../../core/session';
import { GRADES, Grade } from './people.service';
import { Rate, RatesService } from './rates.service';

const PCT = /^\d{1,5}(\.\d{1,2})?$/;

// The rate of each grade on the sheet, and the form that sets a grade's rate.
@Component({
  selector: 'app-rates',
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form (submit)="submit($event)" aria-label="Rate" data-testid="rate-form">
        <label for="rate-form-grade">Grade</label>
        <select
          id="rate-form-grade"
          data-testid="rate-form-grade"
          [value]="grade()"
          (change)="grade.set(gradeOf($event)); error.set(null)"
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
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="rate-form-error">{{ message }}</p>
        }
      </form>
      <table data-testid="rate-list">
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="rateOf(g) === null ? null : 'rate-row-' + g + '-start'">
              <td>{{ g }}</td>
              <td [attr.data-testid]="'rate-current-' + g">
                <span [attr.data-testid]="rateOf(g) === null ? null : 'rate-pct'">{{ rateOf(g) ?? 'n/a' }}</span>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
    table {
      border-collapse: collapse;
      margin-bottom: 1rem;
    }
    th,
    td {
      text-align: left;
      padding: 0.25rem 0.75rem 0.25rem 0;
    }
  `,
})
export class Rates {
  private readonly session = inject(Session);
  private readonly service = inject(RatesService);

  /** The sheet's rates, by grade. */
  readonly rates = input.required<Rate[]>();
  /** A grade's rate was set. */
  readonly saved = output<Rate>();

  protected readonly grades = GRADES;
  protected readonly grade = signal<Grade>('A');
  protected readonly pct = signal('');
  protected readonly error = signal<string | null>(null);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected gradeOf(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  protected rateOf(grade: Grade): string | null {
    return this.rates().find((r) => r.grade === grade)?.pct ?? null;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const pct = this.pct().trim();
    if (!PCT.test(pct)) {
      this.error.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    try {
      const rate = await this.service.set(sheet, this.grade(), pct);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(rate);
      this.pct.set('');
    } catch {
      this.error.set('That rate could not be set.');
    }
  }
}
