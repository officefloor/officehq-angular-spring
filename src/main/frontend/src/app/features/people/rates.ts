import { Component, computed, inject, input, output, signal } from '@angular/core';
import { Session } from '../../core/session';
import { GRADES, Grade } from './people.service';
import { Rate, RatePreview, RatesService, rateOn } from './rates.service';

const PCT = /^\d{1,5}(\.\d{1,2})?$/;

// The rates of each grade on the sheet, each from its from-date (through its to-date, if any), with the one in force on the
// sheet's today, and the form that sets a grade's rate from a date.
@Component({
  selector: 'app-rates',
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
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
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
          <p role="alert" class="error" data-testid="rate-form-error">{{ message }}</p>
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
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === previewGrade()">{{ g }}</option>
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
            Claims changed: <span data-testid="rate-preview-count">{{ p.count }}</span>,
            difference: <span data-testid="rate-preview-diff">{{ p.diff }}</span>
          </p>
        }
        @if (previewError(); as message) {
          <p role="alert" class="error" data-testid="rate-preview-error">{{ message }}</p>
        }
      </form>
      <table data-testid="rate-list">
        <caption>Rate in force on {{ session.today() }}</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <td>{{ g }}</td>
              <td [attr.data-testid]="'rate-current-' + g">
                <span [attr.data-testid]="currentOf(g) === null ? null : 'rate-pct'">{{ currentOf(g) ?? 'n/a' }}</span>
              </td>
            </tr>
          }
        </tbody>
      </table>
      @if (sorted().length > 0) {
        <table data-testid="rate-history">
          <caption>All rates</caption>
          <thead>
            <tr>
              <th scope="col">Grade</th>
              <th scope="col">From</th>
              <th scope="col">To</th>
              <th scope="col">Rate (%)</th>
            </tr>
          </thead>
          <tbody>
            @for (r of sorted(); track r.grade + '-' + (r.from ?? 'start')) {
              <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
                <td>{{ r.grade }}</td>
                <td data-testid="rate-from">{{ r.from ?? 'the start' }}</td>
                <td data-testid="rate-to">{{ r.to ?? '' }}</td>
                <td data-testid="rate-pct">{{ r.pct }}</td>
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
  /** The rates by grade, then by from-date (one with none first). */
  protected readonly sorted = computed(() =>
    [...this.rates()].sort(
      (a, b) => a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? ''),
    ),
  );
  /** A grade's rate was set. */
  readonly saved = output<Rate>();

  protected readonly grades = GRADES;
  protected readonly grade = signal<Grade>('A');
  protected readonly pct = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly previewGrade = signal<Grade>('A');
  protected readonly previewPct = signal('');
  protected readonly previewed = signal<RatePreview | null>(null);
  protected readonly previewError = signal<string | null>(null);
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
