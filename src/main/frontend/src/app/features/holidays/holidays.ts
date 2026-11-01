import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A holiday on the sheet. */
interface HolidayView {
  id: number;
  date: string;
}

/** A real calendar date written yyyy-mm-dd. */
function isDate(value: string): boolean {
  const at = Date.parse(`${value}T00:00:00Z`);
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(at) && new Date(at).toISOString().slice(0, 10) === value;
}

// The sheet's holidays, each date at most once. A claim's due-by counts its terms in business days,
// skipping weekends and them; adding one fixes again every due-by on or after it of a claim not SETTLED or VOID.
@Component({
  selector: 'app-holidays',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="holidays-heading">
      <h2 id="holidays-heading">Holidays</h2>
      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a holiday">
        <label for="holiday-date">Holiday</label>
        <input id="holiday-date" data-testid="holiday-date" formControlName="date" placeholder="yyyy-mm-dd" />
        <button type="submit" data-testid="holiday-add">Add holiday</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="holiday-error">{{ error() }}</p>
        }
      </form>
      <ul aria-label="Holidays">
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

  protected readonly holidays = signal<HolidayView[]>([]);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({ date: new FormControl('', { nonNullable: true }) });
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.holidays.set([]);
      this.error.set('');
      if (id) void this.load(id);
    });
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/holidays`;
  }

  private async load(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.get<HolidayView[]>(this.base(sheet), { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.holidays.set(list);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the holidays of this sheet.');
    }
  }

  /** Add the date to the sheet's holidays; a date already on the list is refused. */
  protected async add(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const date = this.form.controls.date.value.trim();
    if (!isDate(date)) {
      this.error.set('Enter the date as yyyy-mm-dd.');
      return;
    }
    if (this.holidays().some((h) => h.date === date)) {
      this.error.set('That date is already a holiday.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(this.http.post<HolidayView>(this.base(sheet), { date }, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.holidays.update((list) => [...list.filter((h) => h.id !== saved.id), saved].sort((a, b) => a.date.localeCompare(b.date)));
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not add that holiday: it may already be on the list.');
    } finally {
      this.submitting = false;
    }
  }
}
