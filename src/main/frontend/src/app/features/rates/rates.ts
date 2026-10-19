import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Grade } from '../people/people-api';
import { NO_RATE, Rate, RatesApi } from './rates-api';

const PCT = /^\d{1,3}(\.\d{1,2})?$/;

// The rate for each grade on the sheet, and the form that sets one.
@Component({
  selector: 'app-rates',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="rates-heading" data-testid="rates">
      <h2 id="rates-heading">Rates</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="rate-form">
        <label for="rate-form-grade">Grade</label>
        <select id="rate-form-grade" data-testid="rate-form-grade" formControlName="grade">
          @for (g of grades(); track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input id="rate-form-pct" data-testid="rate-form-pct" inputmode="decimal" formControlName="pct" />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
      </form>
      @if (formError(); as e) {
        <p role="alert" data-testid="rate-form-error">{{ e }}</p>
      }
      <table data-testid="rate-list">
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades(); track g) {
            <tr [attr.data-testid]="'rate-row-' + g + '-start'">
              <td data-testid="rate-grade">{{ g }}</td>
              <td data-testid="rate-pct">
                @if (saving() === g) {
                  Saving…
                } @else {
                  <span [attr.data-testid]="'rate-current-' + g">{{ rates().get(g) ?? noRate }}</span>
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

  readonly grades = input.required<Grade[]>();
  /** The sheet's rate for each grade that has one. */
  readonly rates = input.required<ReadonlyMap<Grade, string>>();
  /** A rate was set. */
  readonly saved = output<Rate>();

  protected readonly noRate = NO_RATE;
  /** The grade whose rate is being saved, shown as such until the store has it. */
  protected readonly saving = signal<Grade | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly form = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.formError.set(null));
  }

  protected submit(): void {
    this.formError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.formError.set('Load a sheet first.');
      return;
    }
    const { grade, pct } = this.form.getRawValue();
    const rate = pct.trim();
    if (!PCT.test(rate)) {
      this.formError.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    this.saving.set(grade);
    this.api.set(sheetId, grade, rate).subscribe({
      next: (r) => {
        this.saving.set(null);
        if (this.session.sheetId() === sheetId) this.saved.emit(r);
        this.form.reset({ grade, pct: '' }, { emitEvent: false });
      },
      error: () => {
        this.saving.set(null);
        this.formError.set('Could not set that rate.');
      },
    });
  }
}
