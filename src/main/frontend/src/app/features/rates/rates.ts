import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, switchMap } from 'rxjs';
import { GRADES, Grade } from '../../core/people';
import { NOT_AVAILABLE, RATE_PATTERN, RatePreview, Rates } from '../../core/rates';
import { Sheets } from '../../core/sheet';

// The sheet's rates for each grade: the one in force today, each by from-date, and a form to set one.
@Component({
  selector: 'app-rates',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <ul aria-label="Rate for each grade">
        @for (g of grades; track g) {
          <li>
            Grade {{ g }}, today: <span [attr.data-testid]="'rate-current-' + g">{{ rates.byGrade().get(g) ?? notAvailable }}</span>
            <ul [attr.aria-label]="'Rates of grade ' + g">
              @for (r of rates.ratesByGrade().get(g) ?? []; track r.from) {
                <li [attr.data-testid]="'rate-row-' + g + '-' + (r.from ?? 'start')">
                  <span data-testid="rate-pct">{{ r.rate }}</span>% from {{ r.from ?? 'the start' }}
                  @if (r.to !== null) {
                    through <span data-testid="rate-to">{{ r.to }}</span>
                  }
                </li>
              }
            </ul>
          </li>
        }
      </ul>

      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Set a rate">
        <label for="rate-form-grade">Grade</label>
        <select id="rate-form-grade" data-testid="rate-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input id="rate-form-pct" data-testid="rate-form-pct" formControlName="pct" inputmode="decimal" autocomplete="off" />
        <label for="rate-form-from">From (blank: from the start)</label>
        <input id="rate-form-from" type="date" data-testid="rate-form-from" formControlName="from" />
        <label for="rate-form-to">To (blank: does not end)</label>
        <input id="rate-form-to" type="date" data-testid="rate-form-to" formControlName="to" />
        <button type="submit" data-testid="rate-form-submit" [disabled]="saving()">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err }}</p>
        }
      </form>

      <form [formGroup]="importForm" (ngSubmit)="importRates()" aria-label="Import rates">
        <label for="import-rates">Import rates, one per line as grade,rate,from,to (from and to may be left out)</label>
        <textarea id="import-rates" data-testid="import-rates" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="import-rates-submit" [disabled]="importing()">Import rates</button>
        @if (importSet() !== null) {
          Set <span data-testid="import-rates-set">{{ importSet() }}</span>
        }
        @if (importError(); as err) {
          <p role="alert" data-testid="import-rates-error">{{ err }}</p>
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
        <input id="rate-preview-pct" data-testid="rate-preview-pct" formControlName="pct" inputmode="decimal" autocomplete="off" />
        <button type="submit" data-testid="rate-preview" [disabled]="previewing()">Preview</button>
        @if (previewed(); as p) {
          <p aria-live="polite">
            Claims that would change: <span data-testid="rate-preview-count">{{ p.count }}</span>,
            difference: <span data-testid="rate-preview-diff">{{ p.diff }}</span>
          </p>
        }
        @if (previewError(); as err) {
          <p role="alert" data-testid="rate-preview-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class RatesPanel {
  private readonly sheets = inject(Sheets);
  protected readonly rates = inject(Rates);

  protected readonly grades = GRADES;
  protected readonly notAvailable = NOT_AVAILABLE;
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly form = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  protected readonly importForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  protected readonly importSet = signal<number | null>(null);
  protected readonly importError = signal<string | null>(null);
  protected readonly importing = signal(false);

  protected readonly previewForm = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
  });
  protected readonly previewed = signal<RatePreview | null>(null);
  protected readonly previewError = signal<string | null>(null);
  protected readonly previewing = signal(false);

  constructor() {
    // Each (re)load of a sheet re-reads its rates from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.error.set(null);
          this.previewed.set(null);
          this.previewError.set(null);
          this.importSet.set(null);
          this.importError.set(null);
          return this.rates.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.error.set('Could not read the rates.') });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
    this.previewForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.previewError.set(null));
    this.importForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.importError.set(null));
  }

  /** Set a rate per pasted line, as the rate form would; when any line is refused, none is set. */
  protected importRates(): void {
    if (this.importing()) return;
    // A press clears the last count, so two presses with the same result can be told apart.
    this.importSet.set(null);
    this.importError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const text = this.importForm.getRawValue().text;
    if (text.trim() === '') {
      this.importError.set('Paste at least one line of grade,rate,from,to.');
      return;
    }
    this.importing.set(true);
    this.rates.importRates(sheet.id, text).subscribe({
      next: ({ set }) => {
        this.importing.set(false);
        this.importSet.set(set);
      },
      error: () => {
        this.importing.set(false);
        this.importError.set('A pasted line is not a valid rate; nothing was set.');
      },
    });
  }

  protected preview(): void {
    if (this.previewing()) return;
    // A press clears the last result, so two presses with the same result can be told apart.
    this.previewed.set(null);
    this.previewError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const { grade, pct } = this.previewForm.getRawValue();
    const rate = pct.trim();
    if (!RATE_PATTERN.test(rate)) {
      this.previewError.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    this.previewing.set(true);
    this.rates.preview(sheet.id, grade, rate).subscribe({
      next: (result) => {
        this.previewing.set(false);
        this.previewed.set(result);
      },
      error: () => {
        this.previewing.set(false);
        this.previewError.set('Could not preview that rate.');
      },
    });
  }

  protected submit(): void {
    // A second submit before the first has answered is ignored, so it stores one record.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const { grade, pct, from, to } = this.form.getRawValue();
    const rate = pct.trim();
    if (!RATE_PATTERN.test(rate)) {
      this.error.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    if (from !== '' && to !== '' && to < from) {
      this.error.set('A rate cannot end before it starts.');
      return;
    }
    this.saving.set(true);
    this.rates.set(sheet.id, grade, rate, from === '' ? null : from, to === '' ? null : to).subscribe({
      next: () => {
        this.saving.set(false);
        this.form.reset({ grade, pct: '', from: '', to: '' }, { emitEvent: false });
      },
      error: () => {
        this.saving.set(false);
        this.error.set('Could not set that rate.');
      },
    });
  }
}
