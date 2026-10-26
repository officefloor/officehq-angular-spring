import { HttpErrorResponse } from '@angular/common/http';
import { FigurePipe } from '../../core/figure.pipe';
import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { GRADES, Grade } from '../people/people-api';
import { RatePreview, RateView, RatesApi, rateInForce } from './rates-api';

const PCT = /^\d{1,7}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's rate in force today for each grade, a calendar of the rate in force on the first of
// each month of today's year, and every rate with its from-date and to-date,
// with one form to set a grade's rate from a from-date through a to-date.
@Component({
  selector: 'app-rates',
  imports: [FigurePipe, ReactiveFormsModule],
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
      <form [formGroup]="importForm" (ngSubmit)="importRates()" aria-label="Import rates">
        <label for="import-rates">Import rates, one per line: grade,rate,from,to</label>
        <textarea id="import-rates" data-testid="import-rates" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="import-rates-submit" [disabled]="importing()">Import rates</button>
        @if (importCount() !== null) {
          <p>Set: <strong data-testid="import-rates-set">{{ importCount() }}</strong></p>
        }
        @if (importError()) {
          <p role="alert" data-testid="import-rates-error">{{ importError() }}</p>
        }
      </form>
      <form [formGroup]="previewForm" (ngSubmit)="preview()" aria-label="Preview a rate">
        <label for="rate-preview-grade">Grade</label>
        <select id="rate-preview-grade" data-testid="rate-preview-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="rate-preview-pct">Candidate rate (%)</label>
        <input id="rate-preview-pct" data-testid="rate-preview-pct" formControlName="pct" inputmode="decimal" />
        <button type="submit" data-testid="rate-preview" [disabled]="previewing()">Preview</button>
        @if (previewError()) {
          <p role="alert" data-testid="rate-preview-error">{{ previewError() }}</p>
        }
        @if (previewed(); as p) {
          <p aria-live="polite">
            Claims that would change: <span data-testid="rate-preview-count">{{ p.count }}</span>;
            difference in due: <span data-testid="rate-preview-diff">{{ p.diff | figure }}</span>
          </p>
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
              <td [attr.data-testid]="'rate-current-' + g">{{ currentOf(g) | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      @if (calendarMonths().length) {
        <table aria-label="Rates in force on the first of each month">
          <thead>
            <tr>
              <th scope="col">Grade</th>
              @for (m of calendarMonths(); track m) {
                <th scope="col">{{ m }}</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (g of grades; track g) {
              <tr>
                <th scope="row">{{ g }}</th>
                @for (m of calendarMonths(); track m) {
                  <td [attr.data-testid]="'rate-month-' + g + '-' + m">{{ rateOn(g, m + '-01') | figure }}</td>
                }
              </tr>
            }
          </tbody>
        </table>
      }
      <table data-testid="rates-list" aria-label="All rates">
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Rate (%)</th><th scope="col">Versions</th></tr>
        </thead>
        <tbody>
          @for (r of ownRates(); track r.grade + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? 'start' }}</td>
              <td data-testid="rate-to">{{ r.to ?? '' }}</td>
              <td data-testid="rate-pct">{{ r.pct | figure }}</td>
              <td data-testid="rate-version">{{ r.version }}</td>
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

  /** The sheet's rates, in grade order then by from-date, then its parents'; a grade with no rate is left out. */
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
  protected readonly importForm = new FormGroup({ text: new FormControl('', { nonNullable: true }) });
  protected readonly importing = signal(false);
  protected readonly importError = signal('');
  protected readonly importCount = signal<number | null>(null);
  protected readonly previewGrade = new FormControl<Grade>('A', { nonNullable: true });
  protected readonly previewPct = new FormControl('', { nonNullable: true });
  protected readonly previewForm = new FormGroup({ grade: this.previewGrade, pct: this.previewPct });
  protected readonly previewing = signal(false);
  protected readonly previewError = signal('');
  protected readonly previewed = signal<RatePreview | null>(null);

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    // A preview shown is of the grade and rate it was asked for: changing either clears it.
    this.previewForm.valueChanges.subscribe(() => {
      this.previewError.set('');
      this.previewed.set(null);
    });
    this.importForm.valueChanges.subscribe(() => this.importError.set(''));
    // Each load of a sheet starts the forms without the error or preview from the last one.
    effect(() => {
      this.session.loaded();
      this.error.set('');
      this.previewError.set('');
      this.previewed.set(null);
      this.importError.set('');
      this.importCount.set(null);
      this.importForm.reset({ text: '' }, { emitEvent: false });
    });
  }

  /** The sheet's own rates, leaving out those of its parents. */
  protected readonly ownRates = computed(() => this.rates().filter((r) => r.level === 0));

  private readonly today = computed(() => this.session.sheet()?.today ?? null);

  /** The grade's rate in force on the sheet's today (its own, else its nearest parent's), or n/a when none is. */
  protected currentOf(grade: Grade): string {
    const today = this.today();
    const rate = today === null ? null : rateInForce(this.rates().filter((r) => r.grade === grade), today);
    return rate?.pct ?? 'n/a';
  }

  /** The months (yyyy-mm) of the calendar year of the sheet's today, or none when there is no sheet. */
  protected readonly calendarMonths = computed(() => {
    const year = this.today()?.slice(0, 4);
    return year ? Array.from({ length: 12 }, (_, i) => `${year}-${String(i + 1).padStart(2, '0')}`) : [];
  });

  /** The grade's rate in force on a date (its own, else its nearest parent's), or n/a when none is. */
  protected rateOn(grade: Grade, date: string): string {
    return rateInForce(this.rates().filter((r) => r.grade === grade), date)?.pct ?? 'n/a';
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

  /** Set a rate per line of grade,rate,from,to, all or none. The last count is cleared while the
   *  import runs, so two equal results are still told apart. */
  protected async importRates(): Promise<void> {
    if (this.importing()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.importError.set('');
    this.importCount.set(null);
    const text = this.importForm.getRawValue().text;
    if (!text.trim()) {
      this.importError.set('Paste at least one line of grade,rate,from,to.');
      return;
    }
    this.importing.set(true);
    try {
      const result = await this.api.importRates(sheet.id, text);
      if (this.session.sheet()?.id === sheet.id) {
        this.importCount.set(result.set);
        for (const rate of result.rates) this.saved.emit(rate);
      }
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const reason = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' && e.error.message
        ? ' ' + e.error.message : '';
      this.importError.set('No rates were set: a line was refused.' + reason);
    } finally {
      this.importing.set(false);
    }
  }

  /** Show what the candidate rate would do to the grade's open claims; nothing is saved. The last
   *  result is cleared while the preview runs, so two equal results are still told apart. */
  protected async preview(): Promise<void> {
    if (this.previewing()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.previewError.set('');
    this.previewed.set(null);
    const { grade, pct } = this.previewForm.getRawValue();
    if (!GRADES.includes(grade)) {
      this.previewError.set('Choose a grade of A, B or C.');
      return;
    }
    if (!PCT.test(pct.trim())) {
      this.previewError.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    this.previewing.set(true);
    try {
      const result = await this.api.preview(sheet.id, grade, pct.trim());
      if (this.session.sheet()?.id === sheet.id) this.previewed.set(result);
    } catch {
      this.previewError.set('The rate could not be previewed.');
    } finally {
      this.previewing.set(false);
    }
  }
}
