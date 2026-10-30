import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Holiday {
  id: number;
  /** The holiday's date (yyyy-mm-dd). */
  date: string;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's holidays, earliest first, and the entry that adds one; no date is on the list twice. A claim's
// due-by, fixed when it is added, counts business days, skipping the weekends and the holidays on the sheet then.
@Component({
  selector: 'app-holidays',
  template: `
    <section aria-label="Holidays">
      <h2>Holidays</h2>
      <ul>
        @for (h of holidays(); track h.id) {
          <li [attr.data-testid]="'holiday-row-' + h.date">{{ h.date }}</li>
        }
      </ul>
      <form (submit)="save($event)" aria-label="Add holiday">
        <label for="holiday-date">Date</label>
        <input
          id="holiday-date"
          type="date"
          data-testid="holiday-date"
          [value]="date()"
          (input)="date.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="holiday-add">Add holiday</button>
        @if (error()) {
          <p role="alert" data-testid="holiday-error">{{ error() }}</p>
        }
      </form>
    </section>
  `,
})
export class Holidays {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly holidays = signal<Holiday[]>([]);
  protected readonly date = signal('');
  protected readonly error = signal('');
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.holidays.set([]);
      this.date.set('');
      this.error.set('');
      if (sheet) void this.reload(sheet);
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const date = this.date().trim();
    if (!DATE.test(date)) {
      this.error.set('Enter a date.');
      return;
    }
    if (this.holidays().some((h) => h.date === date)) {
      this.error.set('That date is already a holiday on this sheet.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Holiday>(`/api/sheets/${encodeURIComponent(sheet)}/holidays`, { date }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.holidays.update((hs) => [...hs.filter((h) => h.id !== saved.id), saved].sort((a, b) => a.date.localeCompare(b.date)));
      this.date.set('');
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const answer = err instanceof HttpErrorResponse ? err : null;
      const message = answer?.error?.message
        ?? (answer?.status === 409 ? 'That date is already a holiday on this sheet.' : 'Could not add the holiday.');
      this.error.set(message);
    } finally {
      this.busy = false;
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const holidays = await firstValueFrom(this.http.get<Holiday[]>(`/api/sheets/${encodeURIComponent(sheet)}/holidays`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.holidays.set(holidays);
  }
}
