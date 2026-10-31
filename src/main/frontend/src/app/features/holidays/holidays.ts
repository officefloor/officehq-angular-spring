import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, switchMap } from 'rxjs';
import { Holidays } from '../../core/holidays';
import { Sheets } from '../../core/sheet';

/** A date the form accepts: yyyy-mm-dd. */
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's holidays: a claim whose date plus its terms falls on one is due by the next day that is not a holiday.
@Component({
  selector: 'app-holidays',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="holidays-heading">
      <h3 id="holidays-heading">Holidays</h3>
      <p>A claim made while a date is on this list is not due by it, but by the next day that is not a holiday.</p>
      <ul aria-label="Holidays">
        @for (h of holidays.all(); track h.id) {
          <li [attr.data-testid]="'holiday-row-' + h.date">{{ h.date }}</li>
        }
      </ul>

      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a holiday">
        <label for="holiday-date">Holiday</label>
        <input id="holiday-date" type="date" data-testid="holiday-date" formControlName="date" />
        <button type="submit" data-testid="holiday-add" [disabled]="saving()">Add holiday</button>
        @if (error(); as err) {
          <p role="alert" data-testid="holiday-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class HolidaysPanel {
  protected readonly holidays = inject(Holidays);
  private readonly sheets = inject(Sheets);

  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly form = new FormGroup({
    date: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its holidays from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.error.set(null);
          return this.holidays.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.error.set('Could not read the holidays.') });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected add(): void {
    // A second submit before the first has answered is ignored, so it stores one holiday.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const date = this.form.controls.date.value.trim();
    if (!DATE_PATTERN.test(date)) {
      this.error.set('Choose the date of the holiday.');
      return;
    }
    this.saving.set(true);
    this.holidays.add(sheet.id, date).subscribe({
      next: () => this.saving.set(false),
      error: () => {
        this.saving.set(false);
        this.error.set('Could not add the holiday: that date may already be on the list.');
      },
    });
  }
}
