import { Component, effect, inject, signal, untracked } from '@angular/core';
import { HolidayView, HolidaysApi } from '../../core/holidays';
import { Session } from '../../core/session';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's holidays, listed by date, and a form to add one; a date already on the list is refused.
// A claim's due-by, fixed when it is added, is moved past the holidays on the list then.
@Component({
  selector: 'app-holidays',
  template: `
    <section aria-labelledby="holidays-heading">
      <h2 id="holidays-heading">Holidays</h2>
      <form (submit)="add($event)" aria-label="Add a holiday">
        <label for="holiday-date">Date</label>
        <input
          id="holiday-date"
          type="date"
          data-testid="holiday-date"
          [value]="date()"
          (input)="date.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="holiday-add">Add holiday</button>
        @if (error(); as message) {
          <p role="alert" data-testid="holiday-error">{{ message }}</p>
        }
      </form>
      <ul aria-label="Holidays">
        @for (h of holidays(); track h.id) {
          <li [attr.data-testid]="'holiday-row-' + h.date">
            <span data-testid="holiday-row-date">{{ h.date }}</span>
          </li>
        }
      </ul>
    </section>
  `,
})
export class Holidays {
  private readonly session = inject(Session);
  private readonly api = inject(HolidaysApi);

  protected readonly holidays = signal<HolidayView[]>([]);
  protected readonly date = signal('');
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  constructor() {
    // Every sheet load re-reads the sheet's holidays from the store.
    effect(() => {
      const sheet = this.session.sheet();
      const loads = this.session.loads();
      untracked(() => {
        this.holidays.set([]);
        this.error.set(null);
        if (sheet) void this.reload(sheet, loads);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const date = this.date().trim();
    if (!DATE.test(date)) {
      this.error.set('Pick a date for the holiday.');
      return;
    }
    if (this.holidays().some((h) => h.date === date)) {
      this.error.set(`${date} is already a holiday.`);
      return;
    }
    this.busy = true;
    try {
      const saved = await this.api.add(sheet, date);
      this.holidays.update((all) => [...all, saved].sort((a, b) => a.date.localeCompare(b.date)));
      this.date.set('');
    } catch {
      this.error.set(`${date} could not be added as a holiday: it may already be on the list.`);
    } finally {
      this.busy = false;
    }
  }

  private async reload(sheet: string, loads: number): Promise<void> {
    const holidays = await this.api.list(sheet);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.holidays.set(holidays);
  }
}
