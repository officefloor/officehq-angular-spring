import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Grade } from '../../core/people';
import { PCT, Rate, RatePreview, Rates, pctOn } from '../../core/rates';
import { Session } from '../../core/session';

// The rates for each grade on the sheet, the one in force on the sheet's today, and the form that
// sets one.
@Component({
  selector: 'app-rate-form',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form data-testid="rate-form" [formGroup]="form" (ngSubmit)="submit()">
        <label for="rate-form-grade">Grade</label>
        <select id="rate-form-grade" data-testid="rate-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input id="rate-form-pct" data-testid="rate-form-pct" formControlName="pct" inputmode="decimal" />
        <label for="rate-form-from">From</label>
        <input id="rate-form-from" type="date" data-testid="rate-form-from" formControlName="from" />
        <label for="rate-form-to">To</label>
        <input id="rate-form-to" type="date" data-testid="rate-form-to" formControlName="to" />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error()) {
          <p role="alert" data-testid="rate-form-error">{{ error() }}</p>
        }
      </form>
      <form data-testid="rate-preview-form" [formGroup]="previewForm" (ngSubmit)="preview()">
        <label for="rate-preview-grade">Preview grade</label>
        <select id="rate-preview-grade" data-testid="rate-preview-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="rate-preview-pct">Candidate rate (%)</label>
        <input id="rate-preview-pct" data-testid="rate-preview-pct" formControlName="pct" inputmode="decimal" />
        <button type="submit" data-testid="rate-preview">Preview</button>
        @if (previewError()) {
          <p role="alert" data-testid="rate-preview-error">{{ previewError() }}</p>
        }
        @if (previewResult(); as r) {
          <p aria-live="polite">
            Claims that would change: <span data-testid="rate-preview-count">{{ r.count }}</span>;
            difference: <span data-testid="rate-preview-diff">{{ r.diff }}</span>
          </p>
        }
      </form>
      <table data-testid="rates-current">
        <caption>In force on {{ session.today() }}</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ current().get(g) ?? 'n/a' }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table data-testid="rates">
        <caption>All rates</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (r of own(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? 'start' }}</td>
              <td data-testid="rate-to">{{ r.to ?? '…' }}</td>
              <td data-testid="rate-pct">{{ r.pct }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class RateForm {
  private readonly api = inject(Rates);
  protected readonly session = inject(Session);

  /** The rates set on the sheet, by grade and then from-date, then those of its ancestors. */
  readonly rates = input.required<Rate[]>();
  /** The rates set on the sheet itself. */
  protected readonly own = computed(() => this.rates().filter((r) => r.level === 0));
  /** A rate set through the form. */
  readonly saved = output<Rate>();

  protected readonly grades: Grade[] = ['A', 'B', 'C'];
  protected readonly error = signal('');
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly form = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });
  protected readonly previewForm = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
  });
  protected readonly previewError = signal('');
  /** The last preview's result; cleared while a preview runs and when the preview form changes. */
  protected readonly previewResult = signal<RatePreview | null>(null);
  /** Whether a preview is waiting for its answer: a second one meanwhile is ignored. */
  private previewing = false;
  /** Each grade's rate in force on the sheet's today; a grade with none is missing. */
  protected readonly current = computed(() => {
    const today = this.session.today();
    const by = new Map<Grade, string>();
    if (today === null) return by;
    for (const g of this.grades) {
      const pct = pctOn(this.rates(), g, today);
      if (pct !== undefined) by.set(g, pct);
    }
    return by;
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.previewForm.valueChanges.subscribe(() => {
      this.previewError.set('');
      this.previewResult.set(null);
    });
  }

  protected async preview(): Promise<void> {
    if (this.previewing) return;
    this.previewError.set('');
    this.previewResult.set(null);
    const { grade, pct } = this.previewForm.getRawValue();
    if (!this.grades.includes(grade)) {
      this.previewError.set('Choose a grade of A, B or C.');
      return;
    }
    if (!PCT.test(pct.trim())) {
      this.previewError.set('Enter a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    this.previewing = true;
    try {
      this.previewResult.set(await this.api.preview(grade, pct.trim()));
    } catch {
      this.previewError.set('The preview could not be made.');
    } finally {
      this.previewing = false;
    }
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { grade, pct, from, to } = this.form.getRawValue();
    if (!this.grades.includes(grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    if (!PCT.test(pct.trim())) {
      this.error.set('Enter a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    const day = from.trim();
    if (day !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(day)) {
      this.error.set('Enter a from-date, or leave it blank for a rate that always applied.');
      return;
    }
    const end = to.trim();
    if (end !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(end)) {
      this.error.set('Enter a to-date, or leave it blank for a rate with no end.');
      return;
    }
    if (day !== '' && end !== '' && end < day) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    this.submitting = true;
    try {
      this.saved.emit(await this.api.set(grade, pct.trim(), day === '' ? null : day, end === '' ? null : end));
      this.form.reset({ grade: 'A', pct: '', from: '', to: '' }, { emitEvent: false });
    } catch {
      this.error.set('The rate could not be set.');
    } finally {
      this.submitting = false;
    }
  }
}
