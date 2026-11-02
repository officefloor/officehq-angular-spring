import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Grade } from '../../core/people';
import { PCT, Rate, Rates } from '../../core/rates';

// The rate for each grade on the sheet, and the form that sets one.
@Component({
  selector: 'app-rate-form',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form data-testid="rate-form" [formGroup]="form" (ngSubmit)="submit()">
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
          <p role="alert" data-testid="rate-form-error">{{ error() }}</p>
        }
      </form>
      <table data-testid="rates">
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="rateOf(g) === undefined ? null : 'rate-row-' + g + '-start'">
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">
                @if (rateOf(g); as pct) {
                  <span data-testid="rate-pct">{{ pct }}</span>
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
export class RateForm {
  private readonly api = inject(Rates);

  /** The rates set on the sheet. */
  readonly rates = input.required<Rate[]>();
  /** A rate set through the form. */
  readonly saved = output<Rate>();

  protected readonly grades: Grade[] = ['A', 'B', 'C'];
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected rateOf(grade: Grade): string | undefined {
    return this.rates().find((r) => r.grade === grade)?.pct;
  }

  protected async submit(): Promise<void> {
    this.error.set('');
    const { grade, pct } = this.form.getRawValue();
    if (!this.grades.includes(grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    if (!PCT.test(pct.trim())) {
      this.error.set('Enter a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    try {
      this.saved.emit(await this.api.set(grade, pct.trim()));
      this.form.reset({ grade: 'A', pct: '' }, { emitEvent: false });
    } catch {
      this.error.set('The rate could not be set.');
    }
  }
}
