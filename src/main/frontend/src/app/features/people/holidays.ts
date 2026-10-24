import { Component, effect, inject, output, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Holiday, HolidaysService } from './holidays.service';
import { FigurePipe } from '../../core/figure.pipe';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's holidays, one line each, earliest first, and the entry by which one is added (a date
// already on the list is refused). Read from the store every time a sheet is loaded. An added holiday
// re-fixes the due-by of the sheet's claims on the server; `added` tells the screen to re-read them.
@Component({
  selector: 'app-holidays',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="holidays-heading">
      <h2 id="holidays-heading">Holidays</h2>
      <form (submit)="submit($event)" aria-label="Holiday" data-testid="holiday-form">
        <label for="holiday-date">Date</label>
        <input id="holiday-date" data-testid="holiday-date" placeholder="YYYY-MM-DD" [value]="date()" (input)="date.set(value($event)); error.set(null)" />
        <button type="submit" data-testid="holiday-add">Add holiday</button>
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="holiday-error">{{ (message) | figure }}</p>
        }
      </form>
      @if (holidays().length > 0) {
        <ul data-testid="holiday-list">
          @for (h of holidays(); track h.id) {
            <li [attr.data-testid]="'holiday-row-' + h.date">{{ (h.date) | figure }}</li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class Holidays {
  private readonly session = inject(Session);
  private readonly service = inject(HolidaysService);

  readonly added = output<void>();

  protected readonly holidays = signal<Holiday[]>([]);
  protected readonly date = signal('');
  protected readonly error = signal<string | null>(null);
  private saving = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      this.session.loads();
      this.holidays.set([]);
      this.date.set('');
      this.error.set(null);
      if (sheet) void this.read(sheet);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const date = this.date().trim();
    if (!DATE.test(date)) {
      this.error.set('Type the date as YYYY-MM-DD.');
      return;
    }
    if (this.holidays().some((h) => h.date === date)) {
      this.error.set('That date is already a holiday.');
      return;
    }
    this.saving = true;
    try {
      const all = await this.service.add(sheet, date);
      if (this.session.sheetId() !== sheet) return;
      this.holidays.set(all);
      this.date.set('');
      this.added.emit();
    } catch {
      this.error.set('That date is already a holiday, or is not a date.');
    } finally {
      this.saving = false;
    }
  }

  private async read(sheet: string): Promise<void> {
    try {
      const all = await this.service.list(sheet);
      if (this.session.sheetId() === sheet) this.holidays.set(all);
    } catch {
      // The holidays could not be read; they show as none.
    }
  }
}
