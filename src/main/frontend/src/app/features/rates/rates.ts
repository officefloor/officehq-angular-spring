import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Grade } from '../people/people-api';
import { NO_RATE, Rate, RatesApi } from './rates-api';

const PCT = /^\d{1,3}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** A row of the rate list: a grade's rate from a from-date, or from the start when it has none. */
interface RateRow {
  key: string;
  grade: Grade;
  from: string | null;
  to: string | null;
  rate: string | undefined;
}

// The rates for each grade on the sheet, each from its from-date, the rate each grade has in force
// on the sheet's today, and the form that sets one.
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
        <label for="rate-form-from">From (optional)</label>
        <input id="rate-form-from" data-testid="rate-form-from" type="date" formControlName="from" />
        <label for="rate-form-to">To (optional)</label>
        <input id="rate-form-to" data-testid="rate-form-to" type="date" formControlName="to" />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
      </form>
      @if (formError(); as e) {
        <p role="alert" data-testid="rate-form-error">{{ e }}</p>
      }
      <table data-testid="rate-current-list">
        <caption>In force on {{ session.today() ?? '' }}</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades(); track g) {
            <tr>
              <td>{{ g }}</td>
              <td [attr.data-testid]="'rate-current-' + g">{{ current().get(g) ?? noRate }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table data-testid="rate-list">
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">From</th>
            <th scope="col">To</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (r of rows(); track r.key) {
            <tr [attr.data-testid]="'rate-row-' + r.key">
              <td data-testid="rate-grade">{{ r.grade }}</td>
              <td data-testid="rate-from">{{ r.from ?? 'Start' }}</td>
              <td data-testid="rate-to">{{ r.to ?? '' }}</td>
              <td data-testid="rate-pct">
                @if (saving() === r.key) {
                  Saving…
                } @else {
                  {{ r.rate ?? noRate }}
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
  protected readonly session = inject(Session);
  private readonly api = inject(RatesApi);

  readonly grades = input.required<Grade[]>();
  /** The sheet's rates. */
  readonly rates = input.required<readonly Rate[]>();
  /** The rate each grade with one has in force on the sheet's today. */
  readonly current = input.required<ReadonlyMap<Grade, string>>();
  /** A rate was set. */
  readonly saved = output<Rate>();

  protected readonly noRate = NO_RATE;
  /** Each grade's rate from the start (n/a when it has none), then its rates by from-date. */
  protected readonly rows = computed<RateRow[]>(() =>
    this.grades().flatMap((grade) => {
      const own = this.rates().filter((r) => r.grade === grade);
      const dated = own
        .filter((r) => r.from !== null)
        .sort((a, b) => (a.from! < b.from! ? -1 : 1))
        .map((r) => ({ key: rowKey(grade, r.from), grade, from: r.from, to: r.to, rate: r.rate }));
      const start = own.find((r) => r.from === null);
      return [{ key: rowKey(grade, null), grade, from: null, to: start?.to ?? null, rate: start?.rate }, ...dated];
    }),
  );
  /** The row whose rate is being saved, shown as such until the store has it. */
  protected readonly saving = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly form = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.formError.set(null));
  }

  protected submit(): void {
    if (this.saving() !== null) return;
    this.formError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.formError.set('Load a sheet first.');
      return;
    }
    const { grade, pct, from: fromValue, to: toValue } = this.form.getRawValue();
    const rate = pct.trim();
    if (!PCT.test(rate)) {
      this.formError.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    const from = fromValue.trim() || null;
    if (from !== null && !DATE.test(from)) {
      this.formError.set('Pick a from-date, or leave it empty for from the start.');
      return;
    }
    const to = toValue.trim() || null;
    if (to !== null && !DATE.test(to)) {
      this.formError.set('Pick a to-date, or leave it empty for no end.');
      return;
    }
    if (to !== null && from !== null && to < from) {
      // The refused dates are cleared, so they are picked again rather than carried into the next rate.
      this.form.patchValue({ from: '', to: '' }, { emitEvent: false });
      this.formError.set('The to-date cannot be before the from-date.');
      return;
    }
    this.saving.set(rowKey(grade, from));
    this.api.set(sheetId, grade, rate, from, to).subscribe({
      next: (r) => {
        this.saving.set(null);
        if (this.session.sheetId() === sheetId) this.saved.emit(r);
        this.form.reset({ grade, pct: '', from: '', to: '' }, { emitEvent: false });
      },
      error: () => {
        this.saving.set(null);
        this.formError.set('Could not set that rate.');
      },
    });
  }
}

function rowKey(grade: Grade, from: string | null): string {
  return `${grade}-${from ?? 'start'}`;
}
