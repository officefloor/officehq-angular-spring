import { HttpClient } from '@angular/common/http';
import { Component, Injectable, effect, inject, input, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { SettingStore } from '../settings/settings';

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
  /** The most OPEN and PENDING claims a person may have in the period; null for no cap. */
  cap: number | null;
  /** Each person's carried figure for the period fixed when it was closed or soft-closed (two
   *  decimals), by person id; empty while it is open. */
  closedPersonCarried: Record<string, string>;
  /** Each person's opening balance for the period (two decimals), by person id: added to their
   *  carried figure for it while it is open. */
  openings: Record<string, string>;
  /** Whether a steward has locked the period: a locked period cannot be reopened. */
  locked: boolean;
}

/** The period whose dates contain a date; null when none does. Periods never overlap, so at most
 *  one can. */
export function periodOf(periods: Period[], date: string): Period | null {
  return periods.find((p) => p.from <= date && date <= p.to) ?? null;
}

/** The fiscal year a date (yyyy-mm-dd) falls in, FY<yyyy>, named by the calendar year its first
 *  day falls in: a fiscal year runs twelve months from the first of the start month (1 to 12). */
export function fiscalYearOf(date: string, startMonth: number): string {
  const year = Number(date.slice(0, 4));
  const month = Number(date.slice(5, 7));
  return 'FY' + String(month >= startMonth ? year : year - 1).padStart(4, '0');
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const YEAR = /^\d{4}$/;
const CAP = /^\d+$/;

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

  /** Add a period to the active sheet, capping each person's OPEN and PENDING claims in it (null:
   *  no cap); the server refuses a name taken, dates running backwards, an overlap with another
   *  period or a cap below 1. */
  async add(name: string, from: string, to: string, cap: number | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<Period>(`/api/sheets/${encodeURIComponent(sheet)}/periods`, { name, from, to, cap }),
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

  /** Generate the twelve monthly periods of fiscal year FY<year> on the active sheet, as generate
   *  does. Resolves to how many were made. */
  async generateFiscalYear(year: number): Promise<number> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const result = await firstValueFrom(
      this.http.post<{ made: number }>(`/api/sheets/${encodeURIComponent(sheet)}/periods/generate-fy`, { year }),
    );
    if (this.session.sheetId() !== sheet) return result.made;
    const list = await firstValueFrom(
      this.http.get<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods`),
    );
    if (this.session.sheetId() === sheet) this.periods.set(list);
    return result.made;
  }

  /** Roll fiscal year FY<year> over on the active sheet: the next year's twelve periods are generated
   *  and each person's open figure carried into its first; the server refuses anyone but a steward
   *  or a year with no periods. Resolves to how many periods were made. */
  async rollover(year: number): Promise<number> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const load = this.session.loads();
    const result = await firstValueFrom(
      this.http.post<{ made: number }>(`/api/sheets/${encodeURIComponent(sheet)}/periods/rollover`, { year }),
    );
    if (this.session.sheetId() === sheet) await this.read(sheet, load);
    return result.made;
  }

  /** Undo the last rollover on the active sheet: the periods it generated are removed and the
   *  openings it set taken back; the server refuses anyone but the steward who made it, a later day,
   *  or a generated period a claim belongs to. */
  async undoRollover(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const load = this.session.loads();
    await firstValueFrom(this.http.post<void>(`/api/sheets/${encodeURIComponent(sheet)}/periods/rollover/undo`, {}));
    if (this.session.sheetId() === sheet) await this.read(sheet, load);
  }

  /** Generate the four calendar quarters of a year, <yyyy>-Q1 to <yyyy>-Q4, on the active sheet,
   *  leaving out quarters whose name is taken or whose days overlap a period. Resolves to how many
   *  were made. */
  async generateQuarters(year: number): Promise<number> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const result = await firstValueFrom(
      this.http.post<{ made: number }>(`/api/sheets/${encodeURIComponent(sheet)}/periods/generate-quarters`, {
        year,
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

  /** Lock (or unlock) a period on the active sheet; the server refuses anyone but a steward, and a
   *  period already locked (or not locked). */
  async setLocked(name: string, locked: boolean): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<Period>(
        `/api/sheets/${encodeURIComponent(sheet)}/periods/${encodeURIComponent(name)}/${locked ? 'lock' : 'unlock'}`,
        null,
      ),
    );
    if (this.session.sheetId() !== sheet) return;
    this.periods.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
  }

  /** Add each pasted line of name,period,amount on the active sheet to that person's opening balance
   *  for that period; none when any line is refused. How many lines went through; the sheet's periods
   *  are then re-read. */
  async importOpenings(text: string): Promise<number> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const load = this.session.loads();
    const result = await firstValueFrom(
      this.http.post<{ added: number }>(`/api/sheets/${encodeURIComponent(sheet)}/periods/openings`, { text }),
    );
    if (this.session.sheetId() === sheet) await this.read(sheet, load);
    return result.added;
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
        <label for="period-form-cap">Open claims cap per person (blank: none)</label>
        <input
          id="period-form-cap"
          data-testid="period-form-cap"
          inputmode="numeric"
          [value]="cap()"
          (input)="cap.set(value($event)); error.set(null)"
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
      <form (submit)="generateFiscalYear($event)">
        <label for="period-generate-fy-year">Fiscal year (yyyy)</label>
        <input
          id="period-generate-fy-year"
          data-testid="period-generate-fy-year"
          inputmode="numeric"
          [value]="fiscalYear()"
          (input)="fiscalYear.set(value($event)); generateError.set(null)"
        />
        <button type="submit" data-testid="period-generate-fy">Generate fiscal year</button>
      </form>
      <form (submit)="generateQuarters($event)">
        <label for="period-generate-quarters-year">Calendar year (yyyy)</label>
        <input
          id="period-generate-quarters-year"
          data-testid="period-generate-quarters-year"
          inputmode="numeric"
          [value]="quartersYear()"
          (input)="quartersYear.set(value($event)); generateError.set(null)"
        />
        <button type="submit" data-testid="period-generate-quarters">Generate quarters</button>
      </form>
      @if (generateError(); as message) {
        <p role="alert" data-testid="period-generate-error">{{ message }}</p>
      }
      <form (submit)="rollover($event)" aria-label="Roll the year over">
        <label for="rollover-year">Fiscal year to roll over (yyyy)</label>
        <input
          id="rollover-year"
          data-testid="rollover-year"
          inputmode="numeric"
          [value]="rolloverYear()"
          (input)="rolloverYear.set(value($event)); rolloverError.set(null)"
        />
        <button type="submit" data-testid="rollover">Roll over</button>
        <button type="button" data-testid="rollover-undo" (click)="undoRollover()">Undo last rollover</button>
        @if (rolledOver() !== null) {
          Made <strong data-testid="rollover-made">{{ rolledOver() }}</strong>
        }
      </form>
      @if (rolloverError(); as message) {
        <p role="alert" data-testid="rollover-error">{{ message }}</p>
      }
      <form (submit)="importOpenings($event)" aria-label="Import opening balances">
        <label for="opening-import">Opening balances, one per line as name,period,amount</label>
        <textarea
          id="opening-import"
          data-testid="opening-import"
          rows="4"
          [value]="openingText()"
          (input)="openingText.set(value($event)); openingError.set(null)"
        ></textarea>
        <button type="submit" data-testid="opening-import-submit">Import opening balances</button>
        @if (openingsAdded() !== null) {
          Added <strong data-testid="opening-import-done">{{ openingsAdded() }}</strong>
        }
      </form>
      @if (openingError(); as message) {
        <p role="alert" data-testid="opening-error">{{ message }}</p>
      }
      <table>
        <thead>
          <tr>
            <th scope="col">Period</th>
            <th scope="col">From</th>
            <th scope="col">To</th>
            <th scope="col">Fiscal year</th>
            <th scope="col">Due</th>
            <th scope="col">Closed</th>
            <th scope="col">Cap</th>
            <th scope="col">Locked</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of store.periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-fy">{{ fiscalYearOf(p.from, settings.fiscalStartMonth()) }}</td>
              <td data-testid="period-due-total">{{ dueTotals().get(p.id) ?? '0.00' }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</td>
              <td data-testid="period-cap">{{ p.cap ?? '' }}</td>
              <td data-testid="period-locked">{{ p.locked ? 'yes' : 'no' }}</td>
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
                <button
                  type="button"
                  data-testid="period-lock"
                  [attr.aria-label]="'Lock period ' + p.name"
                  (click)="setLocked(p, true)"
                >
                  Lock
                </button>
                <button
                  type="button"
                  data-testid="period-unlock"
                  [attr.aria-label]="'Unlock period ' + p.name"
                  (click)="setLocked(p, false)"
                >
                  Unlock
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
  private readonly session = inject(Session);
  protected readonly settings = inject(SettingStore);
  protected readonly fiscalYearOf = fiscalYearOf;
  /** Each period's due-total (two decimals), by period id: the sum of the people's figures for it. */
  readonly dueTotals = input.required<Map<number, string>>();

  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly cap = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly generateFrom = signal('');
  protected readonly generateTo = signal('');
  protected readonly fiscalYear = signal('');
  protected readonly quartersYear = signal('');
  /** How many periods the last generate made; null while one runs or before any. */
  protected readonly generated = signal<number | null>(null);
  protected readonly generateError = signal<string | null>(null);
  private readonly generating = signal(false);
  /** The error from the last close or reopen refused, and the period it belongs to. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);
  protected readonly openingText = signal('');
  /** How many lines the last opening import added; null before one, or while one runs. */
  protected readonly openingsAdded = signal<number | null>(null);
  protected readonly openingError = signal<string | null>(null);
  private readonly openingBusy = signal(false);
  protected readonly rolloverYear = signal('');
  /** How many periods the last rollover made; null before one, or while one runs. */
  protected readonly rolledOver = signal<number | null>(null);
  protected readonly rolloverError = signal<string | null>(null);
  private readonly rollingOver = signal(false);

  constructor() {
    // A loaded sheet starts the opening box afresh: its last count and error were for the sheet before.
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.openingText.set('');
        this.openingsAdded.set(null);
        this.openingError.set(null);
        this.rolledOver.set(null);
        this.rolloverError.set(null);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Add each pasted line of name,period,amount to that person's opening balance for that period;
   *  when any line is refused nothing is added. How many lines went through then shows. */
  protected async importOpenings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.openingBusy()) return;
    this.openingError.set(null);
    this.openingsAdded.set(null);
    const text = this.openingText();
    if (!text.trim()) {
      this.openingError.set('Paste at least one line of name,period,amount.');
      return;
    }
    this.openingBusy.set(true);
    try {
      this.openingsAdded.set(await this.store.importOpenings(text));
      this.openingText.set('');
    } catch {
      this.openingError.set(
        'The opening balances could not be imported: every line needs a person on the sheet by name or alias, ' +
          'a period on the sheet and an amount of 0.00 or more with up to two decimals.',
      );
    } finally {
      this.openingBusy.set(false);
    }
  }

  /** Roll the fiscal year typed (yyyy) over into the next; how many periods it made then shows. */
  protected async rollover(event: Event): Promise<void> {
    event.preventDefault();
    if (this.rollingOver()) return;
    this.rolloverError.set(null);
    this.rolledOver.set(null);
    const year = this.rolloverYear().trim();
    if (!YEAR.test(year) || Number(year) < 1 || Number(year) > 9997) {
      this.rolloverError.set('Type the fiscal year (yyyy) to roll over.');
      return;
    }
    this.rollingOver.set(true);
    try {
      this.rolledOver.set(await this.store.rollover(Number(year)));
    } catch {
      this.rolloverError.set(`FY${year} could not be rolled over: only a steward may, and the year needs periods.`);
    } finally {
      this.rollingOver.set(false);
    }
  }

  /** Undo the last rollover; a refusal shows the rollover error. */
  protected async undoRollover(): Promise<void> {
    if (this.rollingOver()) return;
    this.rolloverError.set(null);
    this.rollingOver.set(true);
    try {
      await this.store.undoRollover();
    } catch {
      this.rolloverError.set(
        'The last rollover could not be undone: only the steward who made it may, on the same day, ' +
          'and no claim may belong to a period it generated.',
      );
    } finally {
      this.rollingOver.set(false);
    }
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
        message: `The period ${period.name} could not be reopened: it must be closed and not locked, and your role allowed to reopen a period.`,
      });
    }
  }

  /** Lock or unlock a period; anyone but a steward, or a period already so, is refused on its row. */
  protected async setLocked(period: Period, locked: boolean): Promise<void> {
    this.rowError.set(null);
    try {
      await this.store.setLocked(period.name, locked);
    } catch {
      this.rowError.set({
        id: period.id,
        message: locked
          ? `The period ${period.name} could not be locked: it may be locked already, and only a steward may lock a period.`
          : `The period ${period.name} could not be unlocked: it must be locked, and only a steward may unlock a period.`,
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

  /** Generate the twelve monthly periods of the fiscal year typed (yyyy), as generate does. */
  protected async generateFiscalYear(event: Event): Promise<void> {
    event.preventDefault();
    if (this.generating()) return;
    this.generateError.set(null);
    this.generated.set(null);
    const year = this.fiscalYear().trim();
    if (!YEAR.test(year) || Number(year) < 1 || Number(year) > 9998) {
      this.generateError.set('Type the fiscal year (yyyy) to generate the periods of.');
      return;
    }
    this.generating.set(true);
    try {
      this.generated.set(await this.store.generateFiscalYear(Number(year)));
    } catch {
      this.generateError.set(`The periods of FY${year} could not be generated.`);
    } finally {
      this.generating.set(false);
    }
  }

  /** Generate the four calendar quarters of the year typed (yyyy), leaving out quarters taken. */
  protected async generateQuarters(event: Event): Promise<void> {
    event.preventDefault();
    if (this.generating()) return;
    this.generateError.set(null);
    this.generated.set(null);
    const year = this.quartersYear().trim();
    if (!YEAR.test(year) || Number(year) < 1) {
      this.generateError.set('Type the calendar year (yyyy) to generate the quarters of.');
      return;
    }
    this.generating.set(true);
    try {
      this.generated.set(await this.store.generateQuarters(Number(year)));
    } catch {
      this.generateError.set(`The quarters of ${year} could not be generated.`);
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
    const cap = this.cap().trim();
    if (cap && (!CAP.test(cap) || Number(cap) < 1)) {
      this.error.set('Type the cap as a whole number of at least 1, or leave it blank for none.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.add(name, from, to, cap ? Number(cap) : null);
      this.name.set('');
      this.from.set('');
      this.to.set('');
      this.cap.set('');
    } catch {
      this.error.set(`The period ${name} could not be added: its name is taken or it overlaps another period.`);
    } finally {
      this.busy.set(false);
    }
  }
}
