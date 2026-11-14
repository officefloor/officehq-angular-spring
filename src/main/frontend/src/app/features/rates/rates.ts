import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { GRADES } from '../people/people-api';
import { PCT_PATTERN, Rate, RatesApi, rateError } from './rates-api';

// The sheet's rate for each grade, and the form that sets a grade's rate.
@Component({
  selector: 'app-rates',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Set rate">
        <label for="rate-form-grade">Grade</label>
        <select id="rate-form-grade" data-testid="rate-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input id="rate-form-pct" data-testid="rate-form-pct" formControlName="pct" inputmode="decimal" autocomplete="off" />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err }}</p>
        }
      </form>
      <table data-testid="rates-list">
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            @if (rateOf(g); as r) {
              <tr [attr.data-testid]="'rate-row-' + g + '-start'">
                <th scope="row" data-testid="rate-grade">{{ g }}</th>
                <td [attr.data-testid]="'rate-current-' + g"><span data-testid="rate-pct">{{ r.pct }}</span></td>
              </tr>
            } @else {
              <tr>
                <th scope="row">{{ g }}</th>
                <td [attr.data-testid]="'rate-current-' + g">n/a</td>
              </tr>
            }
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Rates {
  private readonly session = inject(Session);
  private readonly api = inject(RatesApi);

  /** The sheet's rates, one per grade that has one. */
  readonly rates = input.required<Rate[]>();
  /** A rate just set, for the sheet to take in. */
  readonly saved = output<Rate>();

  protected readonly grades = GRADES;
  protected readonly error = signal<string | null>(null);

  protected readonly form = new FormGroup({
    grade: new FormControl<string>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
  }

  protected rateOf(grade: string): Rate | undefined {
    return this.rates().find((r) => r.grade === grade);
  }

  protected async submit(): Promise<void> {
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const { grade, pct } = this.form.getRawValue();
    if (!(GRADES as readonly string[]).includes(grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    const value = pct.trim();
    if (!PCT_PATTERN.test(value)) {
      this.error.set('Enter a percent of 0.00 or more with up to two decimals.');
      return;
    }
    try {
      const rate = await this.api.set(sheetId, grade, value);
      if (this.session.sheetId() === sheetId) this.saved.emit(rate);
      this.form.reset({ grade, pct: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(rateError(e));
    }
  }
}
