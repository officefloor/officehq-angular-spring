import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { HolidayView, HolidaysApi } from './holidays-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's holidays in date order, with an entry to add one. A claim's terms count business
// days, skipping every Saturday, Sunday and holiday on the sheet now, so adding one moves every
// due-by it falls before.
@Component({
  selector: 'app-holidays',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="holidays-heading">
      <h2 id="holidays-heading">Holidays</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Add a holiday">
        <label for="holiday-date">Date</label>
        <input id="holiday-date" type="date" data-testid="holiday-date" formControlName="date" />
        <button type="submit" data-testid="holiday-add" [disabled]="saving()">Add holiday</button>
        @if (error()) {
          <p role="alert" data-testid="holiday-error">{{ error() }}</p>
        }
      </form>
      <ul data-testid="holidays-list" aria-label="Holidays">
        @for (h of holidays(); track h.id) {
          <li [attr.data-testid]="'holiday-row-' + h.date">{{ h.date }}</li>
        }
      </ul>
    </section>
  `,
})
export class Holidays {
  private readonly session = inject(Session);
  private readonly api = inject(HolidaysApi);

  protected readonly date = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ date: this.date });
  protected readonly holidays = signal<HolidayView[]>([]);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.date.valueChanges.subscribe(() => this.error.set(''));
    // Each load of a sheet re-reads its holidays from the store and starts the entry afresh.
    effect(() => {
      const sheet = this.session.loaded();
      this.error.set('');
      this.date.reset('', { emitEvent: false });
      this.holidays.set([]);
      if (sheet) void this.load(sheet.id);
    });
  }

  private async load(sheetId: string): Promise<void> {
    try {
      const list = await this.api.list(sheetId);
      if (this.session.sheet()?.id === sheetId) this.holidays.set(list);
    } catch {
      if (this.session.sheet()?.id === sheetId) this.error.set('The holidays could not be loaded.');
    }
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const date = this.date.value.trim();
    if (!DATE.test(date)) {
      this.error.set('Type a date as yyyy-mm-dd.');
      return;
    }
    if (this.holidays().some((h) => h.date === date)) {
      this.error.set('That date is already a holiday.');
      return;
    }
    this.saving.set(true);
    try {
      const added = await this.api.add(sheet.id, date);
      if (this.session.sheet()?.id === sheet.id) {
        this.holidays.update((list) => [...list, added].sort((a, b) => a.date.localeCompare(b.date)));
        this.date.reset('', { emitEvent: false });
      }
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(status === 409 ? 'That date is already a holiday.'
        : status === 403 ? 'Your role may not add a holiday on this sheet.' : 'The holiday could not be added.');
    } finally {
      this.saving.set(false);
    }
  }
}
