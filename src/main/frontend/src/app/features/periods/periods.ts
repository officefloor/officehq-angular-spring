import { HttpClient } from '@angular/common/http';
import { Component, Injectable, effect, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Period {
  id: number;
  /** Unique on the sheet; it names the period's row. */
  name: string;
  /** The first day of the period (yyyy-mm-dd), inclusive. */
  from: string;
  /** The last day of the period (yyyy-mm-dd), inclusive. */
  to: string;
  /** Whether the period is closed: no claim dated in it can change. */
  closed: boolean;
  /** The due-total fixed when the period was closed (two decimals); null while it is open. */
  closedTotal: string | null;
}

/** The period whose dates contain a date; null when none does. Periods never overlap, so at most
 *  one can. */
export function periodOf(periods: Period[], date: string): Period | null {
  return periods.find((p) => p.from <= date && date <= p.to) ?? null;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The active sheet's periods, the earliest first, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class PeriodStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly periods = signal<Period[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.periods.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Add a period to the active sheet; the server refuses a name taken, dates running backwards or
   *  an overlap with another period. */
  async add(name: string, from: string, to: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<Period>(`/api/sheets/${encodeURIComponent(sheet)}/periods`, { name, from, to }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.periods.update((list) =>
      [...list.filter((p) => p.id !== saved.id), saved].sort((a, b) => a.from.localeCompare(b.from)),
    );
  }

  /** Close a period on the active sheet, fixing its due-total; the server refuses one already
   *  closed. */
  async close(name: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<Period>(
        `/api/sheets/${encodeURIComponent(sheet)}/periods/${encodeURIComponent(name)}/close`,
        null,
      ),
    );
    if (this.session.sheetId() !== sheet) return;
    this.periods.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
  }

  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(
      this.http.get<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods`),
    );
    if (this.session.loads() !== load) return;
    this.periods.set(list);
  }
}

// The sheet's periods, each with the due-total of the claims that count dated in it (fixed once the
// period is closed) and whether it is closed, and the form that adds one.
@Component({
  selector: 'app-periods',
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>
      <form (submit)="submit($event)">
        <label for="period-form-name">Name</label>
        <input
          id="period-form-name"
          data-testid="period-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="period-form-from">From</label>
        <input
          id="period-form-from"
          type="date"
          data-testid="period-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="period-form-to">To</label>
        <input
          id="period-form-to"
          type="date"
          data-testid="period-form-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="period-form-submit">Add period</button>
      </form>
      @if (error(); as message) {
        <p role="alert" data-testid="period-form-error">{{ message }}</p>
      }
      <table>
        <thead>
          <tr>
            <th scope="col">Period</th>
            <th scope="col">From</th>
            <th scope="col">To</th>
            <th scope="col">Due</th>
            <th scope="col">Closed</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of store.periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ p.closedTotal ?? dueTotals().get(p.id) ?? '0.00' }}</td>
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
                @if (rowError(); as e) {
                  @if (e.id === p.id) {
                    <p role="alert" data-testid="period-error">{{ e.message }}</p>
                  }
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
  protected readonly store = inject(PeriodStore);
  /** Each period's due-total (two decimals), by period id. */
  readonly dueTotals = input.required<Map<number, string>>();

  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  /** The error from the last close refused, and the period it belongs to. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Close a period; one already closed is refused on its row. */
  protected async close(period: Period): Promise<void> {
    this.rowError.set(null);
    try {
      await this.store.close(period.name);
    } catch {
      this.rowError.set({ id: period.id, message: `The period ${period.name} could not be closed.` });
    }
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const name = this.name().trim();
    const from = this.from().trim();
    const to = this.to().trim();
    if (!name) {
      this.error.set('Type a name for the period.');
      return;
    }
    if (!DATE.test(from) || !DATE.test(to)) {
      this.error.set('Type the dates (yyyy-mm-dd) the period runs from and to.');
      return;
    }
    if (from > to) {
      this.error.set('The period must not end before it starts.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.add(name, from, to);
      this.name.set('');
      this.from.set('');
      this.to.set('');
    } catch {
      this.error.set(`The period ${name} could not be added: its name is taken or it overlaps another period.`);
    } finally {
      this.busy.set(false);
    }
  }
}
