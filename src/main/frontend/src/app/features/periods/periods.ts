import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, model, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { money } from '../claims/claims';

// The sheet's periods, by from-date, and the form that adds one: a name unique on the sheet and a
// from and to date, both inclusive, no two periods overlapping. A claim belongs to the period whose
// dates contain its date, or to none; each period shows the due of the claims that count in it.
export interface Period {
  id: number;
  name: string;
  from: string;
  to: string;
}

/** A claim's date and its figure in whole cents, for a claim that counts. */
export interface DatedDue {
  date: string;
  cents: number;
}

/** A date as the date input gives it: yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The period whose dates contain a date (yyyy-mm-dd), if any. */
export function periodOn(periods: readonly Period[], date: string): Period | undefined {
  return periods.find((p) => p.from <= date && date <= p.to);
}

@Component({
  selector: 'app-periods',
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>
      <form aria-label="Period" (submit)="save($event)">
        <label for="period-form-name">Name</label>
        <input
          id="period-form-name"
          data-testid="period-form-name"
          [value]="nameInput()"
          (input)="nameInput.set($any($event.target).value); error.set(null)"
        />
        <label for="period-form-from">From (blank: today)</label>
        <input
          id="period-form-from"
          type="date"
          data-testid="period-form-from"
          [value]="fromInput()"
          (input)="fromInput.set($any($event.target).value); error.set(null)"
        />
        <label for="period-form-to">To</label>
        <input
          id="period-form-to"
          type="date"
          data-testid="period-form-to"
          [value]="toInput()"
          (input)="toInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error(); as err) {
          <p role="alert" data-testid="period-form-error">{{ err }}</p>
        }
      </form>
      <table data-testid="periods-list" aria-label="Periods">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">From</th>
            <th scope="col">To</th>
            <th scope="col">Due</th>
          </tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotalOf(p) }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Periods {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's periods by from-date, shared with whoever shows a claim's period. */
  readonly periods = model<Period[]>([]);
  /** The date and figure of each claim that counts. */
  readonly dues = input.required<DatedDue[]>();

  protected readonly nameInput = signal('');
  protected readonly fromInput = signal('');
  protected readonly toInput = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's periods from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.periods.set([]);
      this.nameInput.set('');
      this.fromInput.set('');
      this.toInput.set('');
      this.error.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  /** The due of the claims that count whose date falls in the period. */
  protected dueTotalOf(p: Period): string {
    return money(this.dues().reduce((sum, d) => sum + (p.from <= d.date && d.date <= p.to ? d.cents : 0), 0));
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.post<Period[]>('/api/periods/list', { sheet }));
      if (this.session.sheet() === sheet) this.periods.set(list);
    } catch {
      this.error.set('Could not read the periods on this sheet.');
    }
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const name = this.nameInput().trim();
    const from = this.fromInput().trim() || this.session.today() || '';
    const to = this.toInput().trim();
    if (!/^\S+$/.test(name)) {
      this.error.set('Enter a name for the period, without spaces.');
      return;
    }
    if (this.periods().some((p) => p.name.toLowerCase() === name.toLowerCase())) {
      this.error.set('That period name is already in use on this sheet.');
      return;
    }
    if (!DATE.test(from) || Number.isNaN(Date.parse(from)) || !DATE.test(to) || Number.isNaN(Date.parse(to))) {
      this.error.set('Enter the from and to dates as yyyy-mm-dd.');
      return;
    }
    if (to < from) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    if (this.periods().some((p) => p.from <= to && from <= p.to)) {
      this.error.set('That period overlaps another period.');
      return;
    }
    this.submitting = true;
    try {
      const added = await firstValueFrom(
        this.http.post<Period>('/api/periods/add', { sheet, user: this.session.user(), name, from, to }),
      );
      if (this.session.sheet() !== sheet) return;
      this.periods.update((list) => [...list, added].sort((a, b) => a.from.localeCompare(b.from)));
      this.nameInput.set('');
      this.fromInput.set('');
      this.toInput.set('');
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That period name is in use, or the period overlaps another.'
          : 'Could not add this period.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
