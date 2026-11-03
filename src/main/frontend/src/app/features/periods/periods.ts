import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, model, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { money } from '../claims/claims';

// The sheet's periods, by from-date, and the form that adds one: a name unique on the sheet and a
// from and to date, both inclusive, no two periods overlapping. A claim belongs to the period whose
// dates contain its date, or to none; each period shows the due of the claims that count in it.
// A period can be closed: its due-total is then the one fixed when it was closed, and no claim
// dated inside it can be added, edited, opened, settled or voided. A steward can reopen a closed
// period, making its due-total live again.
export interface Period {
  id: number;
  name: string;
  from: string;
  to: string;
  closed: boolean;
  /** The due-total fixed when the period was closed; null while it is open. */
  closedDue: string | null;
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

/** Whether a date (yyyy-mm-dd) falls inside a closed period. */
export function inClosedPeriod(periods: readonly Period[], date: string): boolean {
  return periodOn(periods, date)?.closed ?? false;
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
            <th scope="col">Closed</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotalOf(p) }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : 'no' }}</td>
              <td>
                <button
                  type="button"
                  data-testid="period-close"
                  [attr.aria-label]="'Close period ' + p.name"
                  (click)="close(p)"
                >
                  Close
                </button>
                <button
                  type="button"
                  data-testid="period-reopen"
                  [attr.aria-label]="'Reopen period ' + p.name"
                  (click)="reopen(p)"
                >
                  Reopen
                </button>
                @if (rowErrors().get(p.id); as err) {
                  <p role="alert" data-testid="period-error">{{ err }}</p>
                }
              </td>
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
  /** The error of each period's last refused action, by period id. */
  protected readonly rowErrors = signal<ReadonlyMap<number, string>>(new Map());
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
      this.rowErrors.set(new Map());
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  /** A closed period's due-total fixed when it was closed; an open period's, the due of the claims
   *  that count whose date falls in it. */
  protected dueTotalOf(p: Period): string {
    if (p.closed && p.closedDue !== null) return p.closedDue;
    return money(this.dues().reduce((sum, d) => sum + (p.from <= d.date && d.date <= p.to ? d.cents : 0), 0));
  }

  /** Close a period, fixing its due-total; the server refuses a period already closed. */
  protected close(p: Period): Promise<void> {
    return this.act(p, 'close', (status) =>
      status === 409 ? 'Period ' + p.name + ' is already closed.' : 'Could not close period ' + p.name + '.',
    );
  }

  /** Reopen a closed period, making its due-total live again; the server refuses anyone but a
   *  steward, and a period that is not closed. */
  protected reopen(p: Period): Promise<void> {
    return this.act(p, 'reopen', (status) =>
      status === 403
        ? 'Only a steward can reopen period ' + p.name + '.'
        : status === 409
          ? 'Period ' + p.name + ' is not closed.'
          : 'Could not reopen period ' + p.name + '.',
    );
  }

  /** Take an action on a period's row, showing the error the server's refusal maps to. */
  private async act(p: Period, action: string, refusal: (status: number) => string): Promise<void> {
    this.rowErrors.update((m) => {
      if (!m.has(p.id)) return m;
      const next = new Map(m);
      next.delete(p.id);
      return next;
    });
    const sheet = this.session.sheet();
    try {
      const changed = await firstValueFrom(
        this.http.post<Period>('/api/periods/' + action, { sheet, user: this.session.user(), id: p.id }),
      );
      if (this.session.sheet() !== sheet) return;
      this.periods.update((list) => list.map((x) => (x.id === changed.id ? changed : x)));
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const err = refusal(e instanceof HttpErrorResponse ? e.status : 0);
      this.rowErrors.update((m) => new Map(m).set(p.id, err));
    }
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
