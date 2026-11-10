import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's holidays, by date, and the entry that adds one; a date already on the list is
// refused. A claim's due-by, fixed when it is added, is moved past the holidays on the sheet then:
// falling on one, it is the next day that is not. A holiday added later moves no claim's due-by.
export interface Holiday {
  id: number;
  date: string;
}

/** A date as the date input gives it: yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

@Component({
  selector: 'app-holidays',
  template: `
    <section aria-labelledby="holidays-heading">
      <h2 id="holidays-heading">Holidays</h2>
      <form aria-label="Holiday" (submit)="add($event)">
        <label for="holiday-date">Date</label>
        <input
          id="holiday-date"
          type="date"
          data-testid="holiday-date"
          [value]="dateInput()"
          (input)="dateInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="holiday-add">Add holiday</button>
        @if (error(); as err) {
          <p role="alert" data-testid="holiday-error">{{ err }}</p>
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
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly holidays = signal<Holiday[]>([]);
  protected readonly dateInput = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's holidays from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.holidays.set([]);
      this.dateInput.set('');
      this.error.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.post<Holiday[]>('/api/holidays/list', { sheet }));
      if (this.session.sheet() === sheet) this.holidays.set(list);
    } catch {
      this.error.set('Could not read the holidays on this sheet.');
    }
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const date = this.dateInput().trim();
    if (!DATE.test(date) || Number.isNaN(Date.parse(date))) {
      this.error.set('Enter the holiday as yyyy-mm-dd.');
      return;
    }
    if (this.holidays().some((h) => h.date === date)) {
      this.error.set('That date is already a holiday.');
      return;
    }
    this.submitting = true;
    try {
      const added = await firstValueFrom(
        this.http.post<Holiday>('/api/holidays/add', { sheet, user: this.session.user(), date }),
      );
      if (this.session.sheet() !== sheet) return;
      this.holidays.update((list) => [...list, added].sort((a, b) => a.date.localeCompare(b.date)));
      this.dateInput.set('');
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That date is already a holiday.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'Your role may not add holidays.'
            : 'Could not add this holiday.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
