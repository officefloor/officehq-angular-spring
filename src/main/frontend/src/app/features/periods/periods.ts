import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, model, signal } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { cents, money } from '../claims/claims';
import { FigurePipe } from '../../core/figure';

// The sheet's periods, by from-date, and the form that adds one: a name unique on the sheet and a
// from and to date, both inclusive, no two periods overlapping. A second form generates a period
// per calendar month (named yyyy-mm) between two dates, leaving out months in use or overlapping; a third
// generates them, the same way, for the twelve months of a fiscal year (FY<yyyy>, named by the calendar
// year its first day falls in; it starts in the sheet's fiscal start month); a fourth makes the four calendar
// quarters of a year (named yyyy-Q1 to yyyy-Q4), the same way. Each period shows the fiscal
// year its from-date falls in. A claim belongs to the period whose
// dates contain its due-by (as fixed when it was added), or to none; each period shows the due of the claims that count in it.
// A period can be closed: its due-total is then the one fixed when it was closed, and no claim
// due by a day inside it can be added, edited, opened, settled or voided. Who may close or reopen a period
// is the sheet's permission matrix (see the settings); by default a steward can reopen a closed
// period, making its due-total live again. Each person's row shows their own due-total for every
// period, fixed the same way while it is closed, and their carried figure into it (fixed the same way);
// a period's due-total is the sum of their due-totals.
// A period can instead be soft-closed (by whoever may close it): its due-total and each person's
// are fixed as for a closed period, but claims due inside it can still change; a claim added then
// with its due-by inside it is a late entry. A soft-closed period can be closed (fixing its figures afresh) or reopened.
// A steward can lock a period and unlock it again; a locked period cannot be reopened.
// Opening balances can be pasted as lines of name,period,amount (a person by name or alias, a period
// on the sheet, an amount of 0.00 or more with two decimals); each adds to the person's opening
// balance for the period, which is added to their carried figure into it. Any refused line refuses the paste.
// An open period counts a claim by the status it had at the end of its to-date, as the claim's status
// log records it (a claim with no status by then does not count), at its figure as shown now.
export interface Period {
  id: number;
  name: string;
  from: string;
  to: string;
  closed: boolean;
  /** Whether the period is soft-closed: its figures fixed, its claims still open to change. */
  softClosed: boolean;
  /** The due-total fixed when the period was closed or soft-closed; null while it is open. */
  closedDue: string | null;
  /** Each person's due-total for the period fixed when it was closed, by person id; null while open. */
  closedDues: Record<string, string> | null;
  /** Each person's carried figure for the period fixed when it was closed, by person id; null while open. */
  closedCarried: Record<string, string> | null;
  /** Each person's opening balance for the period, added to their carried figure into it, by person id. */
  openings: Record<string, string>;
  /** The fiscal year its from-date falls in: FY<yyyy>. */
  fy: string;
  /** At most this many OPEN or PENDING claims per person in it; null for no cap. */
  cap: number | null;
  /** Whether a steward locked the period: a locked period cannot be reopened. */
  locked: boolean;
}

/** A claim's person, due-by (the date that places it in a period) and its figure in whole cents, for a claim that has one. */
export interface DatedDue {
  personId: number;
  date: string;
  cents: number;
  /** Whether the claim counts by the status it had at the end of a day (yyyy-mm-dd). */
  countsAt: (day: string) => boolean;
  /** Whether the claim counts by its status now (OPEN, SETTLED or PENDING), for the classic summary. */
  countsNow: boolean;
  /** Whether the claim counts and was still OPEN or PENDING at the end of a day (yyyy-mm-dd), for the carried figures. */
  openAt: (day: string) => boolean;
}

/** Whether a claim's due falls in an open period: due by a day inside it, counting at the end of its to-date. */
function inPeriod(p: Period, d: DatedDue): boolean {
  return p.from <= d.date && d.date <= p.to && d.countsAt(p.to);
}

/** A date as the date input gives it: yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The period whose dates contain a date (yyyy-mm-dd), if any. */
export function periodOn(periods: readonly Period[], date: string): Period | undefined {
  return periods.find((p) => p.from <= date && date <= p.to);
}

/** A closed period's due-total fixed when it was closed; an open period's, the due of the claims
 *  whose due-by falls in it that counted at the end of its to-date. */
export function periodDueTotal(p: Period, dues: readonly DatedDue[]): string {
  if ((p.closed || p.softClosed) && p.closedDue !== null) return p.closedDue;
  return money(dues.reduce((sum, d) => sum + (inPeriod(p, d) ? d.cents : 0), 0));
}

/** A period's classic due-total: a closed period's fixed when it was closed; an open period's, the
 *  due of the claims whose due-by falls in it, each counted by its status now. */
export function classicPeriodDueTotal(p: Period, dues: readonly DatedDue[]): string {
  if ((p.closed || p.softClosed) && p.closedDue !== null) return p.closedDue;
  return money(dues.reduce((sum, d) => sum + (p.from <= d.date && d.date <= p.to && d.countsNow ? d.cents : 0), 0));
}

/** A person's due-total for a period: for a closed period, the one fixed when it was closed (0.00
 *  for a person added since); for an open one, the due of their claims that counted in it at its end. */
export function personPeriodDueTotal(p: Period, personId: number, dues: readonly DatedDue[]): string {
  if ((p.closed || p.softClosed) && p.closedDues !== null) return p.closedDues[String(personId)] ?? '0.00';
  return money(
    dues.reduce((sum, d) => sum + (d.personId === personId && inPeriod(p, d) ? d.cents : 0), 0),
  );
}

/** A person's carried figure for a period: for a closed or soft-closed period, the one fixed when it was
 *  closed (0.00 for a person added since); for an open one, the due of their claims that count which
 *  belong to a period ending before this one starts and were still OPEN or PENDING at the end of its to-date,
 *  plus their opening balance for it. */
export function personPeriodCarried(
  p: Period, periods: readonly Period[], personId: number, dues: readonly DatedDue[],
): string {
  if ((p.closed || p.softClosed) && p.closedCarried !== null) return p.closedCarried[String(personId)] ?? '0.00';
  const earlier = periods.filter((q) => q.to < p.from);
  const opening = cents(p.openings[String(personId)] ?? '0.00');
  return money(
    opening + dues.reduce((sum, d) => sum + (d.personId === personId && earlier.some((q) => q.from <= d.date && d.date <= q.to)
      && d.openAt(p.to) ? d.cents : 0), 0),
  );
}

/** Whether a date (yyyy-mm-dd) falls inside a closed period. */
export function inClosedPeriod(periods: readonly Period[], date: string): boolean {
  return periodOn(periods, date)?.closed ?? false;
}

@Component({
  selector: 'app-periods',
  imports: [FigurePipe],
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
        <label for="period-form-cap">Open claims per person (blank: no cap)</label>
        <input
          id="period-form-cap"
          data-testid="period-form-cap"
          inputmode="numeric"
          [value]="capInput()"
          (input)="capInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error(); as err) {
          <p role="alert" data-testid="period-form-error">{{ err }}</p>
        }
      </form>
      <form aria-label="Generate monthly periods" (submit)="generate($event)">
        <label for="period-generate-from">Generate from (blank: today)</label>
        <input
          id="period-generate-from"
          type="date"
          data-testid="period-generate-from"
          [value]="generateFrom()"
          (input)="generateFrom.set($any($event.target).value); generateError.set(null)"
        />
        <label for="period-generate-to">To</label>
        <input
          id="period-generate-to"
          type="date"
          data-testid="period-generate-to"
          [value]="generateTo()"
          (input)="generateTo.set($any($event.target).value); generateError.set(null)"
        />
        <button type="submit" data-testid="period-generate">Generate monthly periods</button>
      </form>
      <form aria-label="Generate a fiscal year's periods" (submit)="generateFy($event)">
        <label for="period-generate-fy-year">Fiscal year (FY yyyy)</label>
        <input
          id="period-generate-fy-year"
          data-testid="period-generate-fy-year"
          inputmode="numeric"
          [value]="fyYear()"
          (input)="fyYear.set($any($event.target).value); generateError.set(null)"
        />
        <button type="submit" data-testid="period-generate-fy">Generate fiscal year</button>
      </form>
      <form aria-label="Generate a year's quarterly periods" (submit)="generateQuarters($event)">
        <label for="period-generate-quarters-year">Quarters of year (yyyy)</label>
        <input
          id="period-generate-quarters-year"
          data-testid="period-generate-quarters-year"
          inputmode="numeric"
          [value]="quartersYear()"
          (input)="quartersYear.set($any($event.target).value); generateError.set(null)"
        />
        <button type="submit" data-testid="period-generate-quarters">Generate quarters</button>
      </form>
      @if (generated() !== null) {
        <p role="status">Made <span data-testid="period-generated">{{ generated() }}</span></p>
      }
      @if (generateError(); as err) {
        <p role="alert" data-testid="period-generate-error">{{ err }}</p>
      }
      <form aria-label="Paste opening balances" (submit)="importOpenings($event)">
        <label for="opening-import">Opening balances (name,period,amount per line)</label>
        <textarea
          id="opening-import"
          data-testid="opening-import"
          rows="3"
          [value]="openingText()"
          (input)="openingText.set($any($event.target).value); openingError.set(null)"
        ></textarea>
        <button type="submit" data-testid="opening-import-submit">Paste opening balances</button>
      </form>
      @if (openingDone() !== null) {
        <p role="status">Set <span data-testid="opening-import-done">{{ openingDone() }}</span></p>
      }
      @if (openingError(); as err) {
        <p role="alert" data-testid="opening-error">{{ err }}</p>
      }
      <table data-testid="periods-list" aria-label="Periods">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">From</th>
            <th scope="col">To</th>
            <th scope="col">Due</th>
            <th scope="col">Closed</th>
            <th scope="col">Fiscal year</th>
            <th scope="col">Cap</th>
            <th scope="col">Locked</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotalOf(p) | figure }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</td>
              <td data-testid="period-fy">{{ p.fy }}</td>
              <td data-testid="period-cap">{{ (p.cap ?? '') | figure }}</td>
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
                  (click)="lock(p)"
                >
                  Lock
                </button>
                <button
                  type="button"
                  data-testid="period-unlock"
                  [attr.aria-label]="'Unlock period ' + p.name"
                  (click)="unlock(p)"
                >
                  Unlock
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
  protected readonly capInput = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly generateFrom = signal('');
  protected readonly generateTo = signal('');
  /** The fiscal year typed to generate, by the calendar year its first day falls in. */
  protected readonly fyYear = signal('');
  /** The calendar year typed to generate the quarters of. */
  protected readonly quartersYear = signal('');
  /** How many periods the last generate made; null before one, and while one runs. */
  protected readonly generated = signal<number | null>(null);
  protected readonly generateError = signal<string | null>(null);
  /** The lines of name,period,amount typed to paste as opening balances. */
  protected readonly openingText = signal('');
  /** How many lines the last paste of opening balances set; null before one, and while one runs. */
  protected readonly openingDone = signal<number | null>(null);
  protected readonly openingError = signal<string | null>(null);
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
      this.generateFrom.set('');
      this.generateTo.set('');
      this.fyYear.set('');
      this.quartersYear.set('');
      this.generated.set(null);
      this.generateError.set(null);
      this.openingText.set('');
      this.openingDone.set(null);
      this.openingError.set(null);
      this.rowErrors.set(new Map());
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  protected dueTotalOf(p: Period): string {
    return periodDueTotal(p, this.dues());
  }

  /** Close a period, fixing its due-total; the server refuses a role the permission matrix does
   *  not allow, and a period already closed. */
  protected close(p: Period): Promise<void> {
    return this.act(p, 'close', (status) =>
      status === 403
        ? 'Your role may not close period ' + p.name + '.'
        : status === 409
          ? 'Period ' + p.name + ' is already closed.'
          : 'Could not close period ' + p.name + '.',
    );
  }

  /** Soft-close a period, fixing its figures while its claims can still change; the server refuses
   *  a role the permission matrix does not allow to close, and a period already closed or soft-closed. */
  protected softClose(p: Period): Promise<void> {
    return this.act(p, 'soft-close', (status) =>
      status === 403
        ? 'Your role may not close period ' + p.name + '.'
        : status === 409
          ? 'Period ' + p.name + ' is already closed.'
          : 'Could not soft-close period ' + p.name + '.',
    );
  }

  /** Reopen a closed or soft-closed period, making its due-total live again; the server refuses a role the
   *  permission matrix does not allow, and a period that is not closed. */
  protected reopen(p: Period): Promise<void> {
    return this.act(p, 'reopen', (status) =>
      status === 403
        ? 'Your role may not reopen period ' + p.name + '.'
        : status === 409
          ? (p.locked ? 'Period ' + p.name + ' is locked.' : 'Period ' + p.name + ' is not closed.')
          : 'Could not reopen period ' + p.name + '.',
    );
  }

  /** Lock a period so it cannot be reopened; the server refuses anyone but a steward, and a period already locked. */
  protected lock(p: Period): Promise<void> {
    return this.act(p, 'lock', (status) =>
      status === 403
        ? 'Only a steward may lock period ' + p.name + '.'
        : status === 409
          ? 'Period ' + p.name + ' is already locked.'
          : 'Could not lock period ' + p.name + '.',
    );
  }

  /** Unlock a locked period; the server refuses anyone but a steward, and a period that is not locked. */
  protected unlock(p: Period): Promise<void> {
    return this.act(p, 'unlock', (status) =>
      status === 403
        ? 'Only a steward may unlock period ' + p.name + '.'
        : status === 409
          ? 'Period ' + p.name + ' is not locked.'
          : 'Could not unlock period ' + p.name + '.',
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
    const capText = this.capInput().trim();
    if (capText !== '' && !/^[1-9]\d{0,8}$/.test(capText)) {
      this.error.set('Enter the cap as a whole number above 0, or leave it blank.');
      return;
    }
    const cap = capText === '' ? null : Number(capText);
    if (this.periods().some((p) => p.from <= to && from <= p.to)) {
      this.error.set('That period overlaps another period.');
      return;
    }
    this.submitting = true;
    try {
      const added = await firstValueFrom(
        this.http.post<Period>('/api/periods/add', { sheet, user: this.session.user(), name, from, to, cap }),
      );
      if (this.session.sheet() !== sheet) return;
      this.periods.update((list) => [...list, added].sort((a, b) => a.from.localeCompare(b.from)));
      this.nameInput.set('');
      this.fromInput.set('');
      this.toInput.set('');
      this.capInput.set('');
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

  /** Make one period per calendar month from the from-date's month to the to-date's month, leaving
   *  out a month whose name is in use or whose days overlap a period; shows how many were made. */
  protected async generate(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.generateError.set(null);
    this.generated.set(null);
    const sheet = this.session.sheet();
    const from = this.generateFrom().trim() || this.session.today() || '';
    const to = this.generateTo().trim();
    if (!DATE.test(from) || Number.isNaN(Date.parse(from)) || !DATE.test(to) || Number.isNaN(Date.parse(to))) {
      this.generateError.set('Enter the from and to dates as yyyy-mm-dd.');
      return;
    }
    if (to < from) {
      this.generateError.set('The to-date cannot be before the from-date.');
      return;
    }
    await this.made(sheet, this.http.post<Period[]>('/api/periods/generate', { sheet, user: this.session.user(), from, to }));
  }

  /** Make the twelve monthly periods of a fiscal year, as the monthly generator does; shows how many were made. */
  protected async generateFy(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.generateError.set(null);
    this.generated.set(null);
    const sheet = this.session.sheet();
    const year = this.fyYear().trim();
    if (!/^\d{4}$/.test(year)) {
      this.generateError.set('Enter the fiscal year as yyyy.');
      return;
    }
    await this.made(sheet, this.http.post<Period[]>('/api/periods/generate-fy', { sheet, user: this.session.user(), year }));
  }

  /** Make the four calendar quarters of a year, as the period form adds them; shows how many were made. */
  protected async generateQuarters(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.generateError.set(null);
    this.generated.set(null);
    const sheet = this.session.sheet();
    const year = this.quartersYear().trim();
    if (!/^\d{4}$/.test(year)) {
      this.generateError.set('Enter the year as yyyy.');
      return;
    }
    await this.made(sheet, this.http.post<Period[]>('/api/periods/generate-quarters', { sheet, user: this.session.user(), year }));
  }

  /** Paste opening balances: every line is checked by the server before any is set; shows how many
   *  lines went through, or why the paste was refused. */
  protected async importOpenings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.openingError.set(null);
    this.openingDone.set(null);
    const sheet = this.session.sheet();
    const text = this.openingText();
    if (text.trim() === '') {
      this.openingError.set('Paste at least one line of name,period,amount.');
      return;
    }
    this.submitting = true;
    try {
      const done = await firstValueFrom(
        this.http.post<{ count: number; periods: Period[] }>('/api/periods/openings', {
          sheet, user: this.session.user(), text,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.periods.set(done.periods);
      this.openingDone.set(done.count);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.openingError.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role may not set opening balances.'
          : e instanceof HttpErrorResponse && e.status === 400 && typeof e.error?.message === 'string' && e.error.message
            ? e.error.message
            : 'Could not set the opening balances.',
      );
    } finally {
      this.submitting = false;
    }
  }

  /** Add the periods a generate made to the list and show how many; or show why it was refused. */
  private async made(sheet: string | null, request: Observable<Period[]>): Promise<void> {
    this.submitting = true;
    try {
      const made = await firstValueFrom(request);
      if (this.session.sheet() !== sheet) return;
      this.periods.update((list) => [...list, ...made].sort((a, b) => a.from.localeCompare(b.from)));
      this.generated.set(made.length);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.generateError.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role may not add periods.'
          : 'Could not generate the periods.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
