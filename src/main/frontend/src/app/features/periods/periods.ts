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
  /** Whether the period is soft-closed: its figures are fixed, but claims dated in it can change. */
  softClosed: boolean;
  /** The due-total fixed when the period was closed or soft-closed (two decimals); null while it is open. */
  closedTotal: string | null;
  /** Each person's due-total for the period fixed when it was closed (two decimals), by person id;
   *  empty while it is open. */
  closedPersonTotals: Record<string, string>;
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

  /** Generate one period per calendar month between two dates (blank: the sheet's today) on the
   *  active sheet, leaving out months whose name is taken or whose days overlap a period; the
   *  server refuses a to-date before the from-date. Resolves to how many were made. */
  async generate(from: string, to: string): Promise<number> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const result = await firstValueFrom(
      this.http.post<{ made: number }>(`/api/sheets/${encodeURIComponent(sheet)}/periods/generate`, {
        from: from || null,
        to: to || null,
      }),
    );
    if (this.session.sheetId() !== sheet) return result.made;
    const list = await firstValueFrom(
      this.http.get<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods`),
    );
    if (this.session.sheetId() === sheet) this.periods.set(list);
    return result.made;
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

  /** Soft-close a period on the active sheet, fixing its figures while claims dated in it can still
   *  change; the server refuses one already closed or soft-closed. */
  async softClose(name: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<Period>(
        `/api/sheets/${encodeURIComponent(sheet)}/periods/${encodeURIComponent(name)}/soft-close`,
        null,
      ),
    );
    if (this.session.sheetId() !== sheet) return;
    this.periods.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
  }

  /** Reopen a closed period on the active sheet, making its due-total live again; the server
   *  refuses anyone but a steward, and a period not closed. */
  async reopen(name: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<Period>(
        `/api/sheets/${encodeURIComponent(sheet)}/periods/${encodeURIComponent(name)}/reopen`,
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

// The sheet's periods, each with its due-total, the sum of the people's figures for it (fixed once
// the period is closed) and whether it is closed, and the form that adds one.
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
      <form (submit)="generate($event)">
        <label for="period-generate-from">Generate from</label>
        <input
          id="period-generate-from"
          type="date"
          data-testid="period-generate-from"
          [value]="generateFrom()"
          (input)="generateFrom.set(value($event)); generateError.set(null)"
        />
        <label for="period-generate-to">Generate to</label>
        <input
          id="period-generate-to"
          type="date"
          data-testid="period-generate-to"
          [value]="generateTo()"
          (input)="generateTo.set(value($event)); generateError.set(null)"
        />
        <button type="submit" data-testid="period-generate">Generate monthly periods</button>
        @if (generated() !== null) {
          <output data-testid="period-generated" aria-live="polite">{{ generated() }}</output>
        }
      </form>
      @if (generateError(); as message) {
        <p role="alert" data-testid="period-generate-error">{{ message }}</p>
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
              <td data-testid="period-due-total">{{ dueTotals().get(p.id) ?? '0.00' }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</td>
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
                  data-testid="period-soft-close"
                  [attr.aria-label]="'Soft-close period ' + p.name"
                  (click)="softClose(p)"
                >
                  Soft-close
                </button>
                <button
                  type="button"
                  data-testid="period-reopen"
                  [attr.aria-label]="'Reopen period ' + p.name"
                  (click)="reopen(p)"
                >
                  Reopen
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
  /** Each period's due-total (two decimals), by period id: the sum of the people's figures for it. */
  readonly dueTotals = input.required<Map<number, string>>();

  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly generateFrom = signal('');
  protected readonly generateTo = signal('');
  /** How many periods the last generate made; null while one runs or before any. */
  protected readonly generated = signal<number | null>(null);
  protected readonly generateError = signal<string | null>(null);
  private readonly generating = signal(false);
  /** The error from the last close or reopen refused, and the period it belongs to. */
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
      this.rowError.set({ id: period.id, message: `The period ${period.name} could not be closed: it may be closed already, or your role may not close a period.` });
    }
  }

  /** Soft-close a period; one already closed or soft-closed is refused on its row. */
  protected async softClose(period: Period): Promise<void> {
    this.rowError.set(null);
    try {
      await this.store.softClose(period.name);
    } catch {
      this.rowError.set({
        id: period.id,
        message: `The period ${period.name} could not be soft-closed: it may be closed already, or your role may not close a period.`,
      });
    }
  }

  /** Reopen a closed or soft-closed period; one not closed, or a reopen by anyone but a steward, is refused on
   *  its row. */
  protected async reopen(period: Period): Promise<void> {
    this.rowError.set(null);
    try {
      await this.store.reopen(period.name);
    } catch {
      this.rowError.set({
        id: period.id,
        message: `The period ${period.name} could not be reopened: it must be closed, and your role allowed to reopen a period.`,
      });
    }
  }

  /** Generate monthly periods between the two dates (blank: the sheet's today); a to-date before
   *  the from-date is refused. */
  protected async generate(event: Event): Promise<void> {
    event.preventDefault();
    if (this.generating()) return;
    this.generateError.set(null);
    this.generated.set(null);
    const from = this.generateFrom().trim();
    const to = this.generateTo().trim();
    if ((from && !DATE.test(from)) || (to && !DATE.test(to))) {
      this.generateError.set('Type the dates (yyyy-mm-dd) to generate periods from and to.');
      return;
    }
    if (from && to && from > to) {
      this.generateError.set('The to-date must not be before the from-date.');
      return;
    }
    this.generating.set(true);
    try {
      this.generated.set(await this.store.generate(from, to));
    } catch {
      this.generateError.set('The periods could not be generated: the to-date must not be before the from-date.');
    } finally {
      this.generating.set(false);
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
