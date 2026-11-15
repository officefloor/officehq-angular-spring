import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { money } from '../claims/claims-api';
import { GRADES } from '../people/people-api';
import { PCT_PATTERN, Rate, RatePreview, RatesApi, pctOn, rateError } from './rates-api';

// The rate in force on the sheet's today for each grade, every rate the sheet has for each grade by
// from-date, the form that sets a grade's rate from a date, optionally through a date, and a preview
// of what a candidate rate for a grade would change (nothing is saved).
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
        <label for="rate-form-from">From (blank: always)</label>
        <input id="rate-form-from" type="date" data-testid="rate-form-from" formControlName="from" />
        <label for="rate-form-to">To (blank: for good)</label>
        <input id="rate-form-to" type="date" data-testid="rate-form-to" formControlName="to" />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="previewForm" (ngSubmit)="runPreview()" aria-label="Preview rate">
        <label for="rate-preview-grade">Grade</label>
        <select id="rate-preview-grade" data-testid="rate-preview-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="rate-preview-pct">Candidate rate (%)</label>
        <input id="rate-preview-pct" data-testid="rate-preview-pct" formControlName="pct" inputmode="decimal" autocomplete="off" />
        <button type="submit" data-testid="rate-preview">Preview</button>
        @if (previewError(); as err) {
          <p role="alert" data-testid="rate-preview-error">{{ err }}</p>
        }
        @if (previewed(); as r) {
          <p aria-live="polite">
            Claims changed: <span data-testid="rate-preview-count">{{ r.count }}</span>,
            difference: <span data-testid="rate-preview-diff">{{ money(r.cents) }}</span>
          </p>
        }
      </form>
      <table data-testid="rates-current">
        <caption>In force today</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ currentOf(g) ?? 'n/a' }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table data-testid="rates-list">
        <caption>All rates</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (r of sorted(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row" data-testid="rate-grade">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? '' }}</td>
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

  /** The sheet's rates, each for a grade from a from-date. */
  readonly rates = input.required<Rate[]>();
  /** The rates in force on the sheet, with those it inherits from its parent sheets. */
  readonly effective = input.required<Rate[]>();
  /** The rates by grade, then from-date (an undated rate first). */
  protected readonly sorted = computed(() => [...this.rates()].sort((a, b) =>
    a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? '')));
  /** A rate just set, for the sheet to take in. */
  readonly saved = output<Rate>();

  protected readonly grades = GRADES;
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is ignored. */
  private submitting = false;

  protected readonly form = new FormGroup({
    grade: new FormControl<string>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  /** What a candidate percent for a grade would change on the sheet as it is shown. */
  readonly preview = input.required<(grade: string, pct: string) => RatePreview>();
  protected readonly previewed = signal<RatePreview | null>(null);
  protected readonly previewError = signal<string | null>(null);
  protected readonly money = money;

  protected readonly previewForm = new FormGroup({
    grade: new FormControl<string>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    this.previewForm.valueChanges.subscribe(() => this.previewError.set(null));
    // a preview is of the sheet as it was; once its rates are (re)loaded or changed it is gone
    effect(() => {
      this.rates();
      this.previewed.set(null);
    });
  }

  protected runPreview(): void {
    this.previewed.set(null);
    this.previewError.set(null);
    const { grade, pct } = this.previewForm.getRawValue();
    if (!(GRADES as readonly string[]).includes(grade)) {
      this.previewError.set('Choose a grade.');
      return;
    }
    const value = pct.trim();
    if (!PCT_PATTERN.test(value)) {
      this.previewError.set('Enter a percent of 0.00 or more with up to two decimals.');
      return;
    }
    this.previewed.set(this.preview()(grade, (Number(value)).toFixed(2)));
  }

  /** The grade's rate in force on the sheet's today, or null when it has none then. */
  protected currentOf(grade: string): string | null {
    return pctOn(this.effective().filter((r) => r.grade === grade), this.session.today());
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const { grade, pct, from, to } = this.form.getRawValue();
    if (!(GRADES as readonly string[]).includes(grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    const value = pct.trim();
    if (!PCT_PATTERN.test(value)) {
      this.error.set('Enter a percent of 0.00 or more with up to two decimals.');
      return;
    }
    const fromDate = from.trim();
    if (fromDate !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(fromDate)) {
      this.error.set('Enter a from-date as yyyy-mm-dd, or leave it blank.');
      return;
    }
    const toDate = to.trim();
    if (toDate !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(toDate)) {
      this.error.set('Enter a to-date as yyyy-mm-dd, or leave it blank.');
      return;
    }
    if (fromDate !== '' && toDate !== '' && toDate < fromDate) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    this.submitting = true;
    try {
      const rate = await this.api.set(sheetId, grade, value, fromDate || null, toDate || null);
      if (this.session.sheetId() === sheetId) this.saved.emit(rate);
      this.form.reset({ grade, pct: '', from: '', to: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(rateError(e));
    } finally {
      this.submitting = false;
    }
  }
}
