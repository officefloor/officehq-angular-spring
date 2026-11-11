import { HttpClient } from '@angular/common/http';
import { FigurePipe } from '../../core/locale';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A holiday on the sheet, its date as yyyy-mm-dd. */
interface HolidayView {
  id: number;
  date: string;
}

/** A date typed as yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's holidays: a claim added is never due by one of them.
@Component({
  selector: 'app-holidays',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="holidays-heading">
      <h2 id="holidays-heading">Holidays</h2>
      <form (submit)="add($event)" aria-labelledby="holidays-heading">
        <label for="holiday-date">Holiday date</label>
        <input
          id="holiday-date"
          type="date"
          data-testid="holiday-date"
          [value]="date()"
          (input)="date.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="holiday-add">Add holiday</button>
        @if (error(); as message) {
          <p role="alert" data-testid="holiday-error">{{ message | fig }}</p>
        }
      </form>
      <ul aria-label="Holidays">
        @for (h of holidays(); track h.id) {
          <li [attr.data-testid]="'holiday-row-' + h.date">{{ h.date | fig }}</li>
        }
      </ul>
    </section>
  `,
})
export class Holidays {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly holidays = signal<HolidayView[]>([]);
  protected readonly date = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether an add is waiting for its answer: a second add meanwhile is ignored. */
  private adding = false;
  private reads = 0;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.holidays.set([]);
        this.error.set(null);
        if (sheet) void this.read(sheet);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.adding) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const date = this.date().trim();
    if (!DATE.test(date)) {
      this.error.set('Type the holiday date as yyyy-mm-dd.');
      return;
    }
    if (this.holidays().some((h) => h.date === date)) {
      this.error.set(`${date} is already a holiday.`);
      return;
    }
    this.adding = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<HolidayView>(
          `/api/sheets/${encodeURIComponent(sheet)}/holidays`,
          { date },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) {
        this.holidays.update((list) => [...list, saved].sort((a, b) => a.date.localeCompare(b.date)));
        this.date.set('');
      }
    } catch {
      this.error.set(`The holiday could not be added: ${date} may already be a holiday.`);
    } finally {
      this.adding = false;
    }
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const holidays = await firstValueFrom(
      this.http.get<HolidayView[]>(`/api/sheets/${encodeURIComponent(sheet)}/holidays`, this.session.headers()),
    ).catch(() => null);
    // A later read wins over a slower earlier one.
    if (holidays && read === this.reads && this.session.sheet() === sheet) {
      this.holidays.set(holidays);
    }
  }
}
