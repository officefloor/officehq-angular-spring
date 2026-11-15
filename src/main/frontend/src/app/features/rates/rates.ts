import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { FigurePipe } from '../../core/figure.pipe';
import { Session } from '../../core/session';
import { money } from '../claims/claims-api';
import { PCT_PATTERN, Rate, RatePreview, RatesApi, importRatesError, pctOn, rateError } from './rates-api';

// The rate in force on the sheet's today for each grade, every rate the sheet has for each grade by
// from-date, the form that sets a grade's rate from a date, optionally through a date, an import box
// that sets a rate per pasted line, and a preview of what a candidate rate for a grade would change
// (nothing is saved), and a calendar of each grade's rate on the first of each month of the sheet's
// today's year.
@Component({
  selector: 'app-rates',
  imports: [FigurePipe, ReactiveFormsModule],
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Set rate">
        <label for="rate-form-grade">Grade</label>
        <select id="rate-form-grade" data-testid="rate-form-grade" formControlName="grade">
          @for (g of grades(); track g) {
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
      <form [formGroup]="importForm" (ngSubmit)="importRates()" aria-label="Import rates">
        <label for="import-rates">Import rates (grade,rate,from,to per line)</label>
        <textarea id="import-rates" data-testid="import-rates" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="import-rates-submit">Import rates</button>
        @if (imported() !== null) {
          <p role="status">Set <strong data-testid="import-rates-set">{{ imported() }}</strong></p>
        }
        @if (importErr(); as err) {
          <p role="alert" data-testid="import-rates-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="previewForm" (ngSubmit)="runPreview()" aria-label="Preview rate">
        <label for="rate-preview-grade">Grade</label>
        <select id="rate-preview-grade" data-testid="rate-preview-grade" formControlName="grade">
          @for (g of grades(); track g) {
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
            difference: <span data-testid="rate-preview-diff">{{ money(r.cents) | figure }}</span>
          </p>
        }
      </form>
      <table data-testid="rates-current">
        <caption>In force today</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th><th scope="col">Versions</th></tr>
        </thead>
        <tbody>
          @for (g of grades(); track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ (currentOf(g) ?? 'n/a') | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      @if (calendar(); as cal) {
        <table data-testid="rates-calendar">
          <caption>Rate on the first of each month of {{ cal.year }}</caption>
          <thead>
            <tr>
              <th scope="col">Grade</th>
              @for (m of cal.months; track m) {
                <th scope="col">{{ m }}</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (g of grades(); track g) {
              <tr>
                <th scope="row">{{ g }}</th>
                @for (m of cal.months; track m) {
                  <td [attr.data-testid]="'rate-month-' + g + '-' + m">{{ (monthOf(g, m) ?? 'n/a') | figure }}</td>
                }
              </tr>
            }
          </tbody>
        </table>
      }
      <table data-testid="rates-list">
        <caption>All rates</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Rate (%)</th><th scope="col">Versions</th></tr>
        </thead>
        <tbody>
          @for (r of sorted(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row" data-testid="rate-grade">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? '' }}</td>
              <td data-testid="rate-to">{{ r.to ?? '' }}</td>
              <td data-testid="rate-pct">{{ r.pct | figure }}</td>
              <td data-testid="rate-version">{{ r.version ?? 1 }}</td>
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

  protected readonly grades = this.session.grades;
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is ignored. */
  private submitting = false;

  protected readonly form = new FormGroup({
    grade: new FormControl<string>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  /** The import box: lines of grade,rate,from,to, each a rate as the form sets it. */
  protected readonly importForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  /** How many rates the last import set; null while one runs or before the first. */
  protected readonly imported = signal<number | null>(null);
  protected readonly importErr = signal<string | null>(null);
  private importing = false;

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
    // a preview is of the candidate it was run for; once the form changes it is gone
    this.previewForm.valueChanges.subscribe(() => {
      this.previewError.set(null);
      this.previewed.set(null);
    });
    this.importForm.valueChanges.subscribe(() => this.importErr.set(null));
    // a sheet (re)loaded starts with an empty import box
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.importForm.reset({ text: '' }, { emitEvent: false });
        this.imported.set(null);
        this.importErr.set(null);
      });
    });
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
    if (!this.session.grades().includes(grade)) {
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

  /** The months (yyyy-mm) of the sheet's today's calendar year; null before the sheet has a today. */
  protected readonly calendar = computed(() => {
    const year = this.session.today()?.slice(0, 4);
    if (!year) return null;
    return { year, months: Array.from({ length: 12 }, (_, i) => `${year}-${String(i + 1).padStart(2, '0')}`) };
  });

  /** The grade's rate in force on the first day of a month (yyyy-mm), or null when it has none then. */
  protected monthOf(grade: string, month: string): string | null {
    return pctOn(this.effective().filter((r) => r.grade === grade), `${month}-01`);
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const { grade, pct, from, to } = this.form.getRawValue();
    if (!this.session.grades().includes(grade)) {
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

  /** Set a rate per imported line, all or none; then show how many were set. */
  protected async importRates(): Promise<void> {
    if (this.importing) return;
    this.imported.set(null);
    this.importErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const text = this.importForm.getRawValue().text;
    if (!text.trim()) {
      this.importErr.set('Paste at least one line of grade,rate,from,to.');
      return;
    }
    this.importing = true;
    try {
      const rates = await this.api.importRates(sheetId, text);
      if (this.session.sheetId() !== sheetId) return;
      rates.forEach((r) => this.saved.emit(r));
      this.importForm.reset({ text: '' }, { emitEvent: false });
      this.imported.set(rates.length);
    } catch (e) {
      this.importErr.set(importRatesError(e));
    } finally {
      this.importing = false;
    }
  }
}
