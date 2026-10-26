import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { PeriodView, PeriodsApi } from './periods-api';

const YEAR = /^\d{4}$/;

// A steward's year-end rollover: generates the next fiscal year's twelve monthly periods and carries each
// person's open figure at the end of the typed year into the new year's first period as opening balances.
@Component({
  selector: 'app-rollover',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Roll the fiscal year over">
      <label for="rollover-year">Fiscal year to roll over (yyyy, the calendar year it starts in)</label>
      <input id="rollover-year" data-testid="rollover-year" formControlName="year" inputmode="numeric" />
      <button type="submit" data-testid="rollover" [disabled]="saving()">Roll year over</button>
      @if (made() !== null) {
        <p role="status">Periods made: <span data-testid="rollover-made">{{ made() }}</span></p>
      }
      @if (error()) {
        <p role="alert" data-testid="rollover-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class Rollover {
  private readonly session = inject(Session);
  private readonly api = inject(PeriodsApi);

  /** The sheet's periods, as they stand once the year is rolled over. */
  readonly rolled = output<PeriodView[]>();

  protected readonly form = new FormGroup({
    year: new FormControl('', { nonNullable: true }),
  });
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  /** How many periods the last rollover made; null before one, and while one runs. */
  protected readonly made = signal<number | null>(null);

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    // Each load of a sheet starts the box afresh.
    effect(() => {
      this.session.loaded();
      this.error.set('');
      this.made.set(null);
      this.form.reset({ year: '' }, { emitEvent: false });
    });
  }

  protected async submit(): Promise<void> {
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    this.made.set(null);
    const year = this.form.getRawValue().year.trim();
    if (!YEAR.test(year) || Number(year) < 1 || Number(year) > 9997) {
      this.error.set('Type the fiscal year as the four-digit calendar year it starts in.');
      return;
    }
    this.saving.set(true);
    try {
      const result = await this.api.rollover(sheet.id, Number(year));
      if (this.session.sheet()?.id === sheet.id) {
        this.made.set(result.made);
        this.rolled.emit(result.periods);
      }
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const reason = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' && e.error.message
        ? ' ' + e.error.message : '';
      this.error.set('The year was not rolled over.' + reason);
    } finally {
      this.saving.set(false);
    }
  }
}
