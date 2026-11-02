import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Holiday, Holidays } from '../../core/holidays';
import { Session } from '../../core/session';

const DAY = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's holidays and the entry a day is added with. A claim whose date plus its terms falls on
// a holiday is due by the next day that is not one; a holiday added later leaves due-by dates as
// they are.
@Component({
  selector: 'app-holiday-form',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="holidays-heading">
      <h3 id="holidays-heading">Holidays</h3>
      <form data-testid="holiday-form" [formGroup]="form" (ngSubmit)="submit()">
        <label for="holiday-date">Holiday</label>
        <input id="holiday-date" type="date" data-testid="holiday-date" formControlName="date" />
        <button type="submit" data-testid="holiday-add">Add holiday</button>
        @if (error()) {
          <p role="alert" data-testid="holiday-error">{{ error() }}</p>
        }
      </form>
      <ul data-testid="holidays">
        @for (h of holidays(); track h.id) {
          <li [attr.data-testid]="'holiday-row-' + h.date">{{ h.date }}</li>
        }
      </ul>
    </section>
  `,
})
export class HolidayForm {
  private readonly api = inject(Holidays);
  private readonly session = inject(Session);

  protected readonly holidays = signal<Holiday[]>([]);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    date: new FormControl('', { nonNullable: true }),
  });
  private submitting = false;
  private reads = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.session.loads();
      const sheetId = this.session.sheetId();
      this.holidays.set([]);
      if (sheetId) void this.reload(sheetId);
    });
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { date } = this.form.getRawValue();
    if (!DAY.test(date)) {
      this.error.set('Enter the day as yyyy-mm-dd.');
      return;
    }
    if (this.holidays().some((h) => h.date === date)) {
      this.error.set('That day is already a holiday.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await this.api.add(date);
      this.holidays.update((hs) => [...hs.filter((h) => h.id !== saved.id), saved].sort((a, b) => a.date.localeCompare(b.date)));
      this.form.reset({ date: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That day is already a holiday, or the sheet may not be changed.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'A viewer may not add a holiday.'
            : 'The holiday could not be added.',
      );
    } finally {
      this.submitting = false;
    }
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const holidays = await this.api.list(sheetId);
      if (read === this.reads) this.holidays.set(holidays);
    } catch {
      // the list stays empty; the next load reads it again
    }
  }
}
