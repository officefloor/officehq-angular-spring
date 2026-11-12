import { Component, inject, signal } from '@angular/core';
import { Holidays } from '../../core/holidays';

// The sheet's holidays: a form adds a date (one already on the list is refused), and the list shows them,
// earliest first. A claim's due-by, fixed when it is added, counts business days past the weekends and the
// holidays listed then.
@Component({
  selector: 'app-holidays',
  template: `
    <section aria-label="Holidays" data-testid="holidays">
      <h2>Holidays</h2>
      <form (submit)="add($event)" data-testid="holiday-form">
        <label for="holiday-date">Date (YYYY-MM-DD)</label>
        <input
          id="holiday-date"
          data-testid="holiday-date"
          placeholder="YYYY-MM-DD"
          [value]="date()"
          (input)="date.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="holiday-add" [disabled]="saving()">Add holiday</button>
        @if (error(); as err) {
          <p role="alert" data-testid="holiday-error">{{ err }}</p>
        }
      </form>
      <ul data-testid="holiday-list" aria-label="Holidays">
        @for (h of holidays.holidays(); track h.id) {
          <li [attr.data-testid]="'holiday-row-' + h.date">{{ h.date }}</li>
        }
      </ul>
    </section>
  `,
})
export class HolidaysPanel {
  protected readonly holidays = inject(Holidays);
  protected readonly date = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.holidays.add(this.date());
      if (err) this.error.set(err);
      else this.date.set('');
    } finally {
      this.saving.set(false);
    }
  }
}
