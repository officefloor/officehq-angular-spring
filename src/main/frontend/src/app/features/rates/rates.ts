import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, switchMap } from 'rxjs';
import { GRADES, Grade } from '../../core/people';
import { NOT_AVAILABLE, RATE_PATTERN, Rates } from '../../core/rates';
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

  constructor() {
    // Each (re)load of a sheet re-reads its rates from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.error.set(null);
          return this.rates.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.error.set('Could not read the rates.') });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
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
