import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { GRADES, Grade } from '../people/people-api';
import { RateView, RatesApi, rateInForce } from './rates-api';

const PCT = /^\d{1,3}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's rate in force today for each grade, and every rate with its from-date and to-date,
// with one form to set a grade's rate from a from-date through a to-date.
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
        <label for="rate-form-from">From (blank: from the start)</label>
        <input id="rate-form-from" type="date" data-testid="rate-form-from" formControlName="from" />
        <label for="rate-form-to">To (blank: until the next rate)</label>
        <input id="rate-form-to" type="date" data-testid="rate-form-to" formControlName="to" />
        <button type="submit" data-testid="rate-form-submit" [disabled]="saving()">Set rate</button>
        @if (error()) {
          <p role="alert" data-testid="rate-form-error">{{ error() }}</p>
        }
      </form>
      <table aria-label="Rates in force today">
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate today (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ currentOf(g) }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table data-testid="rates-list" aria-label="All rates">
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (r of rates(); track r.grade + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? 'start' }}</td>
              <td data-testid="rate-to">{{ r.to ?? '' }}</td>
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

  /** The sheet's rates, in grade order then by from-date; a grade with no rate is left out. */
  readonly rates = input.required<RateView[]>();
  readonly saved = output<RateView>();

  protected readonly grades = GRADES;
  protected readonly grade = new FormControl<Grade>('A', { nonNullable: true });
  protected readonly pct = new FormControl('', { nonNullable: true });
  protected readonly from = new FormControl('', { nonNullable: true });
  protected readonly to = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ grade: this.grade, pct: this.pct, from: this.from, to: this.to });
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

  private readonly today = computed(() => this.session.sheet()?.today ?? null);

  /** The grade's rate in force on the sheet's today, or n/a when none is. */
  protected currentOf(grade: Grade): string {
    const today = this.today();
    const rate = today === null ? null : rateInForce(this.rates().filter((r) => r.grade === grade), today);
    return rate?.pct ?? 'n/a';
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const { grade, pct, from, to } = this.form.getRawValue();
    if (!GRADES.includes(grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    if (!PCT.test(pct.trim())) {
      this.error.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    if (from.trim() !== '' && !DATE.test(from.trim())) {
      this.error.set('Type a from-date as yyyy-mm-dd, or leave it blank.');
      return;
    }
    if (to.trim() !== '' && !DATE.test(to.trim())) {
      this.error.set('Type a to-date as yyyy-mm-dd, or leave it blank.');
      return;
    }
    if (from.trim() !== '' && to.trim() !== '' && to.trim() < from.trim()) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    this.saving.set(true);
    try {
      const rate = await this.api.set(sheet.id, grade, pct.trim(), from.trim() === '' ? null : from.trim(),
        to.trim() === '' ? null : to.trim());
      if (this.session.sheet()?.id === sheet.id) this.saved.emit(rate);
    } catch {
      this.error.set('The rate could not be set.');
    } finally {
      this.saving.set(false);
    }
  }
}
