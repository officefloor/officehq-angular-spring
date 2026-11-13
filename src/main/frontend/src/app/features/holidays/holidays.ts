import { HttpClient } from '@angular/common/http';
import { Component, Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Holiday {
  id: number;
  /** The holiday (yyyy-mm-dd); unique on the sheet, it names the holiday's row. */
  date: string;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The active sheet's holidays, the earliest first, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class HolidayStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly holidays = signal<Holiday[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.holidays.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Add a holiday to the active sheet; the server refuses a date already on its list. */
  async add(date: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<Holiday>(`/api/sheets/${encodeURIComponent(sheet)}/holidays`, { date }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.holidays.update((list) =>
      [...list.filter((h) => h.id !== saved.id), saved].sort((a, b) => a.date.localeCompare(b.date)),
    );
  }

  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(
      this.http.get<Holiday[]>(`/api/sheets/${encodeURIComponent(sheet)}/holidays`),
    );
    if (this.session.loads() !== load) return;
    this.holidays.set(list);
  }
}

// The sheet's holidays, which a claim's terms skip as non-business days when it is added, and the entry that
// adds one.
@Component({
  selector: 'app-holidays',
  template: `
    <section aria-labelledby="holidays-heading">
      <h2 id="holidays-heading">Holidays</h2>
      <form (submit)="submit($event)">
        <label for="holiday-date">Holiday</label>
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
      <ul>
        @for (h of store.holidays(); track h.id) {
          <li [attr.data-testid]="'holiday-row-' + h.date">
            <span data-testid="holiday-row-date">{{ h.date }}</span>
          </li>
        }
      </ul>
    </section>
  `,
})
export class Holidays {
  protected readonly store = inject(HolidayStore);
  protected readonly date = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Add the typed date as a holiday; a date already on the list is refused beside the entry. */
  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const date = this.date().trim();
    if (!DATE.test(date)) {
      this.error.set('Type a date (yyyy-mm-dd) for the holiday.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.add(date);
    } catch {
      this.error.set(`The holiday ${date} could not be added: it may be on the list already.`);
    } finally {
      this.busy.set(false);
    }
  }
}
