import { Component, effect, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { GRADES, Grade } from '../people/people-api';
import { RateView, RatesApi } from './rates-api';

const PCT = /^\d{1,3}(\.\d{1,2})?$/;

// The sheet's rate for each grade, with one form to set a grade's rate.
@Component({
  selector: 'app-rates',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Set a rate">
        <label for="rate-form-grade">Grade</label>
        <select id="rate-form-grade" data-testid="rate-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input id="rate-form-pct" data-testid="rate-form-pct" formControlName="pct" inputmode="decimal" />
        <button type="submit" data-testid="rate-form-submit" [disabled]="saving()">Set rate</button>
        @if (error()) {
          <p role="alert" data-testid="rate-form-error">{{ error() }}</p>
        }
      </form>
      <table data-testid="rates-list">
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="rateOf(g) === null ? null : 'rate-row-' + g + '-start'">
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">
                @if (rateOf(g); as pct) {<span data-testid="rate-pct">{{ pct }}</span>} @else {n/a}
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

  /** The sheet's rates; a grade with no rate is left out. */
  readonly rates = input.required<RateView[]>();
  readonly saved = output<RateView>();

  protected readonly grades = GRADES;
  protected readonly grade = new FormControl<Grade>('A', { nonNullable: true });
  protected readonly pct = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ grade: this.grade, pct: this.pct });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    // Each load of a sheet starts the form without the error from the last one.
    effect(() => {
      this.session.loaded();
      this.error.set('');
    });
  }

  protected rateOf(grade: Grade): string | null {
    return this.rates().find((r) => r.grade === grade)?.pct ?? null;
  }

  protected async submit(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const { grade, pct } = this.form.getRawValue();
    if (!GRADES.includes(grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    if (!PCT.test(pct.trim())) {
      this.error.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    this.saving.set(true);
    try {
      const rate = await this.api.set(sheet.id, grade, pct.trim());
      if (this.session.sheet()?.id === sheet.id) this.saved.emit(rate);
    } catch {
      this.error.set('The rate could not be set.');
    } finally {
      this.saving.set(false);
    }
  }
}
