import { Component, computed, inject, input, output, signal } from '@angular/core';
import { Session } from '../../core/session';
import { GradesService } from './grades.service';
import { Grade } from './people.service';
import { Rate, RatePreview, RatesService, rateOn } from './rates.service';
import { FigurePipe } from '../../core/figure.pipe';

const PCT = /^\d{1,5}(\.\d{1,2})?$/;

// The rates of each grade on the sheet, each from its from-date (through its to-date, if any), with the one in force on the
// sheet's today, and the form that sets a grade's rate from a date.
@Component({
  selector: 'app-rates',
  imports: [FigurePipe],
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
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === grade()">{{ (g) | figure }}</option>
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
        <label for="rate-form-from">From</label>
        <input
          id="rate-form-from"
          type="date"
          data-testid="rate-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="rate-form-to">To</label>
        <input
          id="rate-form-to"
          type="date"
          data-testid="rate-form-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="rate-form-error">{{ (message) | figure }}</p>
        }
      </form>
      <form (submit)="importRates($event)" aria-label="Import rates" data-testid="import-rates-form">
        <label for="import-rates">Rates to import, one per line: grade,rate,from,to (the dates may be left out)</label>
        <textarea
          id="import-rates"
          data-testid="import-rates"
          rows="4"
          [value]="importText()"
          (input)="importText.set(value($event)); importError.set(null)"
        ></textarea>
        <button type="submit" data-testid="import-rates-submit">Import rates</button>
        @if (imported() !== null) {
          <span aria-live="polite">Set <span data-testid="import-rates-set">{{ (imported()) | figure }}</span></span>
        }
        @if (importError(); as message) {
          <p role="alert" class="error" data-testid="import-rates-error">{{ (message) | figure }}</p>
        }
      </form>
      <form (submit)="preview($event)" aria-label="Rate preview" data-testid="rate-preview-form">
        <label for="rate-preview-grade">Grade</label>
        <select
          id="rate-preview-grade"
          data-testid="rate-preview-grade"
          [value]="previewGrade()"
          (change)="previewGrade.set(gradeOf($event)); clearPreview()"
        >
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === previewGrade()">{{ (g) | figure }}</option>
          }
        </select>
        <label for="rate-preview-pct">Candidate rate (%)</label>
        <input
          id="rate-preview-pct"
          data-testid="rate-preview-pct"
          inputmode="decimal"
          [value]="previewPct()"
          (input)="previewPct.set(value($event)); clearPreview()"
        />
        <button type="submit" data-testid="rate-preview">Preview</button>
        @if (previewed(); as p) {
          <p aria-live="polite">
            Claims changed: <span data-testid="rate-preview-count">{{ (p.count) | figure }}</span>,
            difference: <span data-testid="rate-preview-diff">{{ (p.diff) | figure }}</span>
          </p>
        }
        @if (previewError(); as message) {
          <p role="alert" class="error" data-testid="rate-preview-error">{{ (message) | figure }}</p>
        }
      </form>
      <table data-testid="rate-list">
        <caption>Rate in force on {{ (session.today()) | figure }}</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades(); track g) {
            <tr>
              <td>{{ (g) | figure }}</td>
              <td [attr.data-testid]="'rate-current-' + g">
                <span [attr.data-testid]="currentOf(g) === null ? null : 'rate-pct'">{{ (currentOf(g) ?? 'n/a') | figure }}</span>
              </td>
            </tr>
          }
        </tbody>
      </table>
      @if (months().length > 0) {
        <table data-testid="rate-calendar">
          <caption>Rate on the first of each month of {{ (months()[0].slice(0, 4)) | figure }}</caption>
          <thead>
            <tr>
              <th scope="col">Grade</th>
              @for (m of months(); track m) {
                <th scope="col">{{ (m) | figure }}</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (g of grades(); track g) {
              <tr>
                <th scope="row">{{ (g) | figure }}</th>
                @for (m of months(); track m) {
                  <td [attr.data-testid]="'rate-month-' + g + '-' + m">{{ (monthOf(g, m) ?? 'n/a') | figure }}</td>
                }
              </tr>
            }
          </tbody>
        </table>
      }
      @if (sorted().length > 0) {
        <table data-testid="rate-history">
          <caption>All rates</caption>
          <thead>
            <tr>
              <th scope="col">Grade</th>
              <th scope="col">From</th>
              <th scope="col">To</th>
              <th scope="col">Rate (%)</th>
              <th scope="col">Versions</th>
            </tr>
          </thead>
          <tbody>
            @for (r of sorted(); track r.grade + '-' + (r.from ?? 'start')) {
              <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
                <td>{{ (r.grade) | figure }}</td>
                <td data-testid="rate-from">{{ (r.from ?? 'the start') | figure }}</td>
                <td data-testid="rate-to">{{ (r.to ?? '') | figure }}</td>
                <td data-testid="rate-pct">{{ (r.pct) | figure }}</td>
                <td data-testid="rate-version">{{ (r.version) | figure }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
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
    caption {
      text-align: left;
      font-weight: bold;
    }
    th,
    td {
      text-align: left;
      padding: 0.25rem 0.75rem 0.25rem 0;
    }
  `,
})
export class Rates {
  protected readonly session = inject(Session);
  private readonly service = inject(RatesService);

  /** The sheet's rates. */
  readonly rates = input.required<Rate[]>();
  /** The sheet's own rates (not its parents'), by grade, then by from-date (one with none first). */
  protected readonly sorted = computed(() =>
    this.rates().filter((r) => (r.level ?? 0) === 0).sort(
      (a, b) => a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? ''),
    ),
  );
  /** Each month (YYYY-MM) of the calendar year of the sheet's today; none until today is read. */
  protected readonly months = computed(() => {
    const year = this.session.today()?.slice(0, 4);
    return year ? Array.from({ length: 12 }, (_, i) => `${year}-${String(i + 1).padStart(2, '0')}`) : [];
  });
  /** A grade's rate was set. */
  readonly saved = output<Rate>();

  protected readonly grades = inject(GradesService).all;
  protected readonly grade = signal<Grade>('A');
  protected readonly pct = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly previewGrade = signal<Grade>('A');
  protected readonly previewPct = signal('');
  protected readonly previewed = signal<RatePreview | null>(null);
  protected readonly previewError = signal<string | null>(null);
  protected readonly importText = signal('');
  /** How many rates the last import set; null before one, and while one runs. */
  protected readonly imported = signal<number | null>(null);
  protected readonly importError = signal<string | null>(null);
  /** An import is waiting for its answer; another meanwhile is ignored. */
  private importing = false;
  /** Counts previews, so only the answer to the latest one shows. */
  private previewSeq = 0;
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private submitting = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected gradeOf(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  /** The grade's rate in force on the sheet's today. */
  protected currentOf(grade: Grade): string | null {
    const today = this.session.today();
    return today === null ? null : rateOn(this.rates(), grade, today);
  }

  /** The grade's rate in force on the first day of a month (YYYY-MM). */
  protected monthOf(grade: Grade, month: string): string | null {
    return rateOn(this.rates(), grade, month + '-01');
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const pct = this.pct().trim();
    if (!PCT.test(pct)) {
      this.error.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    const from = this.from().trim();
    if (from !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.error.set('Type a from-date as YYYY-MM-DD, or leave it blank.');
      return;
    }
    const to = this.to().trim();
    if (to !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      this.error.set('Type a to-date as YYYY-MM-DD, or leave it blank.');
      return;
    }
    if (from !== '' && to !== '' && to < from) {
      this.error.set('A to-date cannot be before the from-date.');
      return;
    }
    this.submitting = true;
    try {
      const rate = await this.service.set(sheet, this.grade(), pct, from || null, to || null);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(rate);
      this.pct.set('');
      this.from.set('');
      this.to.set('');
    } catch {
      this.error.set('That rate could not be set.');
    } finally {
      this.submitting = false;
    }
  }

  /** Set a rate per pasted line, in order; when any line is refused none is set. Shows how many were
   *  set; the old count is gone while it runs. */
  protected async importRates(event: Event): Promise<void> {
    event.preventDefault();
    if (this.importing) return;
    this.importError.set(null);
    this.imported.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (!this.importText().trim()) {
      this.importError.set('Paste a line of grade,rate,from,to.');
      return;
    }
    this.importing = true;
    try {
      const result = await this.service.importRates(sheet, this.importText());
      if (this.session.sheetId() !== sheet) return;
      for (const r of result.rates) this.saved.emit(r);
      this.importText.set('');
      this.imported.set(result.set);
    } catch {
      if (this.session.sheetId() !== sheet) return;
      this.importError.set('Those rates could not be set: each line needs a grade, a rate of 0.00 or more with up to two decimals, and dates as YYYY-MM-DD with the to-date not before the from-date.');
    } finally {
      this.importing = false;
    }
  }

  protected clearPreview(): void {
    this.previewSeq++;
    this.previewed.set(null);
    this.previewError.set(null);
  }

  /** Show what the candidate rate would change; nothing is saved. */
  protected async preview(event: Event): Promise<void> {
    event.preventDefault();
    this.clearPreview();
    const seq = this.previewSeq;
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const pct = this.previewPct().trim();
    if (!PCT.test(pct)) {
      this.previewError.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    try {
      const result = await this.service.preview(sheet, this.previewGrade(), pct);
      if (seq === this.previewSeq && this.session.sheetId() === sheet) this.previewed.set(result);
    } catch {
      if (seq === this.previewSeq) this.previewError.set('That rate could not be previewed.');
    }
  }
}
