import { Component, computed, inject, input, output, signal } from '@angular/core';
import { GRADES, Grade } from '../../core/people';
import { FROM_DATE, NO_RATE, PERCENT, RatePreview, RateView, RatesApi, rateOn } from '../../core/rates';
import { Session } from '../../core/session';

// The sheet's rates: each grade's rate in force on the sheet's today, every rate by grade and
// from-date, and one form to set a grade's rate from a date (or from the beginning of time) through
// a to-date (or with no end).
@Component({
  selector: 'app-rates',
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form (submit)="save($event)" aria-label="Set a rate">
        <label for="rate-form-grade">Grade</label>
        <select
          id="rate-form-grade"
          data-testid="rate-form-grade"
          [value]="grade()"
          (change)="grade.set(gradeOf($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
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
        <label for="rate-form-from">From (empty: from the start)</label>
        <input
          id="rate-form-from"
          type="date"
          data-testid="rate-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="rate-form-to">To (empty: no end)</label>
        <input
          id="rate-form-to"
          type="date"
          data-testid="rate-form-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as message) {
          <p role="alert" data-testid="rate-form-error">{{ message }}</p>
        }
      </form>
      <form (submit)="runPreview($event)" aria-label="Preview a rate">
        <label for="rate-preview-grade">Preview grade</label>
        <select
          id="rate-preview-grade"
          data-testid="rate-preview-grade"
          [value]="previewGrade()"
          (change)="previewGrade.set(gradeOf($event)); clearPreview()"
        >
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
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
        @if (previewError(); as message) {
          <p role="alert" data-testid="rate-preview-error">{{ message }}</p>
        }
        @if (preview(); as p) {
          <p aria-live="polite">
            Claims that would change: <span data-testid="rate-preview-count">{{ p.count }}</span>;
            difference in due: <span data-testid="rate-preview-diff">{{ p.diff }}</span>
          </p>
        }
      </form>
      <table>
        <caption>In force on {{ today() }}</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ currentOf(g) ?? noRate }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
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
          @for (r of listed(); track r.grade + '-' + (r.from ?? 'start')) {
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

  /** The sheet's rates; a grade with no rate is not listed. */
  readonly rates = input.required<RateView[]>();
  /** The rates of the sheet's ancestors, nearest first: a grade with no rate in force falls back to them. */
  readonly inherited = input<RateView[][]>([]);
  /** The sheet's today, on which each grade's rate in force is shown. */
  readonly today = input.required<string>();
  /** A rate set through the form. */
  readonly saved = output<RateView>();

  protected readonly grades = GRADES;
  protected readonly noRate = NO_RATE;
  protected readonly grade = signal<Grade>('A');
  protected readonly pct = signal('');
  /** The from-date typed (yyyy-mm-dd); empty means from the beginning of time. */
  protected readonly from = signal('');
  /** The to-date typed (yyyy-mm-dd); empty means no end. */
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly previewGrade = signal<Grade>('A');
  protected readonly previewPct = signal('');
  /** The last preview's result; cleared when the preview form changes. */
  protected readonly preview = signal<RatePreview | null>(null);
  protected readonly previewError = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  /** Every rate by grade, each grade's from the start, then by from-date. */
  protected readonly listed = computed(() =>
    [...this.rates()].sort((a, b) =>
      a.grade !== b.grade ? a.grade.localeCompare(b.grade) : (a.from ?? '').localeCompare(b.from ?? ''),
    ),
  );

  protected currentOf(grade: Grade): string | null {
    return rateOn(this.rates(), grade, this.today(), this.inherited());
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected gradeOf(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  protected clearPreview(): void {
    this.preview.set(null);
    this.previewError.set(null);
  }

  /** Show what the candidate rate would change for the grade's open claims; nothing is saved. */
  protected async runPreview(event: Event): Promise<void> {
    event.preventDefault();
    this.clearPreview();
    const sheet = this.session.sheet();
    if (!sheet) return;
    const grade = this.previewGrade();
    const pct = this.previewPct().trim();
    if (!PERCENT.test(pct)) {
      this.previewError.set('Type a candidate rate of 0.00 or more, with up to two decimals.');
      return;
    }
    try {
      this.preview.set(await this.api.preview(sheet, grade, pct));
    } catch {
      this.previewError.set(`The rate for grade ${grade} could not be previewed.`);
    }
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const pct = this.pct().trim();
    if (!PERCENT.test(pct)) {
      this.error.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    const from = this.from().trim();
    if (from !== '' && !FROM_DATE.test(from)) {
      this.error.set('Type a from-date as yyyy-mm-dd, or leave it empty for from the start.');
      return;
    }
    const to = this.to().trim();
    if (to !== '' && !FROM_DATE.test(to)) {
      this.error.set('Type a to-date as yyyy-mm-dd, or leave it empty for no end.');
      return;
    }
    if (from !== '' && to !== '' && to < from) {
      // The refused dates are cleared, so they are not carried into the next rate set.
      this.from.set('');
      this.to.set('');
      this.error.set(`A to-date cannot be before the from-date; ${from} and ${to} were cleared.`);
      return;
    }
    this.busy = true;
    try {
      this.saved.emit(await this.api.set(sheet, this.grade(), { pct, from: from || null, to: to || null }));
      this.pct.set('');
      this.from.set('');
      this.to.set('');
    } catch {
      this.error.set(`The rate for grade ${this.grade()} could not be set.`);
    } finally {
      this.busy = false;
    }
  }
}
