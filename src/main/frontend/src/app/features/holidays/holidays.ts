import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface HolidayView {
  id: number;
  date: string;
}

// The active sheet's holidays: a claim's due-by, fixed when the claim is created, counts business days that skip
// weekends and the holidays on the sheet then. A date already on the list is refused.
@Component({
  selector: 'app-holidays',
  imports: [FigPipe],
  template: `
    <section aria-label="Holidays">
      <h2>Holidays</h2>
      <form (submit)="add($event)" aria-label="Add a holiday">
        <label for="holiday-date">Holiday</label>
        <input
          id="holiday-date"
          type="date"
          data-testid="holiday-date"
          [value]="date()"
          (input)="date.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="holiday-add">Add holiday</button>
        @if (error(); as err) {
          <p role="alert" data-testid="holiday-error">{{ err | fig }}</p>
        }
      </form>

      <ul aria-label="Holidays on the sheet">
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

  protected readonly holidays = signal<readonly HolidayView[]>([]);
  protected readonly date = signal('');
  protected readonly error = signal<string | null>(null);
  // A submit in flight: a second one before it answers is ignored.
  private submitting = false;

  constructor() {
    // Re-read the holidays from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.error.set(null);
        this.holidays.set([]);
        if (id) this.load(id).catch(() => this.error.set('Could not load the holidays.'));
      });
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  private async load(sheet: string): Promise<void> {
    const l = await firstValueFrom(
      this.http.get<HolidayView[]>(`/api/sheets/${encodeURIComponent(sheet)}/holidays`),
    );
    if (this.session.sheetId() === sheet) this.holidays.set(l);
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const date = this.date().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date as yyyy-mm-dd.');
      return;
    }
    if (this.holidays().some((h) => h.date === date)) {
      this.error.set(`${date} is already a holiday.`);
      return;
    }
    this.submitting = true;
    try {
      const h = await firstValueFrom(
        this.http.post<HolidayView>(`/api/sheets/${encodeURIComponent(sheet)}/holidays`, { date }),
      );
      if (this.session.sheetId() === sheet) {
        this.holidays.update((l) => [...l, h].sort((a, b) => a.date.localeCompare(b.date)));
      }
    } catch (err) {
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 409
          ? `${date} is already a holiday.`
          : 'Could not add the holiday.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
