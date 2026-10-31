import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, switchMap } from 'rxjs';
import { GRADES, Grade } from '../../core/people';
import { NOT_AVAILABLE, RATE_PATTERN, Rates } from '../../core/rates';
import { Sheets } from '../../core/sheet';

// The sheet's rate for each grade: the current rates and a form to set one.
@Component({
  selector: 'app-rates',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <ul aria-label="Rate for each grade">
        @for (g of grades; track g) {
          @let rate = rates.byGrade().get(g);
          <li>
            Grade {{ g }}: <span [attr.data-testid]="'rate-current-' + g">{{ rate ?? notAvailable }}</span>
            @if (rate !== undefined) {
              <span [attr.data-testid]="'rate-row-' + g + '-start'">
                (<span data-testid="rate-pct">{{ rate }}</span>%)
              </span>
            }
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
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const { grade, pct } = this.form.getRawValue();
    const rate = pct.trim();
    if (!RATE_PATTERN.test(rate)) {
      this.error.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    this.saving.set(true);
    this.rates.set(sheet.id, grade, rate).subscribe({
      next: () => {
        this.saving.set(false);
        this.form.reset({ grade, pct: '' }, { emitEvent: false });
      },
      error: () => {
        this.saving.set(false);
        this.error.set('Could not set that rate.');
      },
    });
  }
}
