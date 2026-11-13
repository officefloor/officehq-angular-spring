import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, isDate } from './session';

export interface Period {
  id: number;
  /** Unique on the sheet. */
  name: string;
  /** The first and last day of the period (YYYY-MM-DD), both inclusive. */
  from: string;
  to: string;
  /** Closed: its due-total is fixed and its claims can no longer change, until a steward reopens it. */
  closed: boolean;
  /** Soft-closed: its due-total and each person's figure are fixed, but its claims can still change. */
  soft: boolean;
  /** The due-total fixed when it was closed or soft-closed, two decimals; null while open. */
  closedTotal: string | null;
  /** Each person's due-total for it fixed when it was closed, two decimals, by person id; empty while
   *  open. A person missing here (added after it closed) has 0.00. */
  closedPersonTotals: Record<number, string>;
  /** Each person's carried figure for it fixed when it was closed or soft-closed, two decimals, by person id;
   *  empty while open. A person missing here has 0.00. */
  closedPersonCarried: Record<number, string>;
  /** Each person's opening balance for it, two decimals, by person id, added to their carried figure; a person
   *  missing here has none. */
  openings: Record<number, string>;
  /** The most OPEN or PENDING claims a person may have in it; null for no cap. */
  cap: number | null;
  /** Locked by a steward: it cannot be reopened until a steward unlocks it. */
  locked: boolean;
}

export interface PeriodFields {
  name: string;
  /** Blank takes the sheet's today. */
  from: string;
  to: string;
  /** A whole number of 1 or more; blank for no cap. */
  cap: string;
}

// The active sheet's periods, by from-date, re-read from the server each time the sheet is loaded.
// No two overlap, so a date falls in at most one of them.
@Injectable({ providedIn: 'root' })
export class Periods {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly periods = signal<Period[]>([]);
  private loads = 0;

  /** The period whose dates contain a date (YYYY-MM-DD), or undefined when none does; a claim belongs to
   *  the one containing its due-by. */
  of(date: string): Period | undefined {
    return this.periods().find((p) => p.from <= date && date <= p.to);
  }

  /** Re-read the active sheet's periods from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.periods.set([]);
    if (!user || !sid) return null;
    try {
      const periods = await firstValueFrom(this.http.get<Period[]>(this.url(sid), { headers: { 'X-User': user } }));
      if (load === this.loads) this.periods.set([...periods].sort(byFrom));
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load periods (${e.status}).` : 'Could not load periods.';
    }
  }

  /** Add a period; an error message when refused. */
  async add(f: PeriodFields): Promise<string | null> {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    const name = f.name.trim();
    const from = f.from.trim() || this.session.today() || '';
    const to = f.to.trim();
    if (!name) return 'Enter a name.';
    if (!isDate(from) || !isDate(to)) return 'Enter the from and to dates as YYYY-MM-DD.';
    if (from > to) return 'The from-date must be on or before the to-date.';
    const capText = f.cap.trim();
    if (capText && !/^[0-9]+$/.test(capText)) return 'Enter the cap as a whole number, or leave it blank.';
    const cap = capText ? Number(capText) : null;
    if (cap !== null && cap < 1) return 'The cap must be 1 or more.';
    if (this.periods().some((p) => p.name.toLowerCase() === name.toLowerCase())) return 'That period name is already in use.';
    const clash = this.periods().find((p) => p.from <= to && from <= p.to);
    if (clash) return `The period overlaps ${clash.name}.`;
    const sid = this.session.sheetId()!;
    try {
      const period = await firstValueFrom(
        this.http.post<Period>(this.url(sid), { name, from, to, cap }, { headers: { 'X-User': this.session.user()! } }),
      );
      if (this.session.sheetId() === sid) this.periods.update((ps) => [...ps, period].sort(byFrom));
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        return 'The period overlaps another, or its name is already in use.';
      }
      if (e instanceof HttpErrorResponse && e.status === 400) {
        return 'Enter a name, the from and to dates as YYYY-MM-DD, and a cap of 1 or more (or none).';
      }
      return e instanceof HttpErrorResponse ? `Could not add period (${e.status}).` : 'Could not add period.';
    }
  }

  /** Generate a period per calendar month from the from-date's month to the to-date's month (a blank
   *  from-date takes the sheet's today), leaving out any month whose name is taken or that overlaps a
   *  period: how many were made, or an error message when refused. */
  async generate(fromDate: string, toDate: string): Promise<number | string> {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    const from = fromDate.trim() || this.session.today() || '';
    const to = toDate.trim();
    if (!isDate(from) || !isDate(to)) return 'Enter the from and to dates as YYYY-MM-DD.';
    if (to < from) return 'The to-date must be on or after the from-date.';
    const sid = this.session.sheetId()!;
    try {
      const made = await firstValueFrom(
        this.http.post<Period[]>(`${this.url(sid)}/generate`, { from, to }, { headers: { 'X-User': this.session.user()! } }),
      );
      if (this.session.sheetId() === sid) this.periods.update((ps) => [...ps, ...made].sort(byFrom));
      return made.length;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not add periods.';
      if (e instanceof HttpErrorResponse && e.status === 409) return 'The to-date must be on or after the from-date.';
      if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter the from and to dates as YYYY-MM-DD.';
      return e instanceof HttpErrorResponse ? `Could not generate periods (${e.status}).` : 'Could not generate periods.';
    }
  }

  /** Generate the twelve monthly periods of a fiscal year (named by the calendar year its first day falls
   *  in) as the generator does: how many were made, or an error message when refused. */
  async generateFiscalYear(year: string): Promise<number | string> {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    const y = year.trim();
    if (!/^\d{4}$/.test(y) || Number(y) < 1 || Number(y) > 9998) return 'Enter the fiscal year as YYYY.';
    const sid = this.session.sheetId()!;
    try {
      const made = await firstValueFrom(
        this.http.post<Period[]>(`${this.url(sid)}/generate-fiscal-year`, { year: Number(y) },
          { headers: { 'X-User': this.session.user()! } }),
      );
      if (this.session.sheetId() === sid) this.periods.update((ps) => [...ps, ...made].sort(byFrom));
      return made.length;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not add periods.';
      if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter the fiscal year as YYYY.';
      return e instanceof HttpErrorResponse ? `Could not generate periods (${e.status}).` : 'Could not generate periods.';
    }
  }

  /** Roll a fiscal year (named by the calendar year its first day falls in) over, a steward only: generate the
   *  next one's periods and carry each person's open figure at the old year's end into the new year's first
   *  period: how many periods were made, or an error message when refused. The periods are re-read after it. */
  async rollover(year: string): Promise<number | string> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    const y = year.trim();
    if (!/^\d{4}$/.test(y) || Number(y) < 1 || Number(y) > 9997) return 'Enter the fiscal year as YYYY.';
    try {
      const made = await firstValueFrom(
        this.http.post<Period[]>(`${this.url(sid)}/rollover`, { year: Number(y) }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) await this.load();
      return made.length;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward may roll the year over.';
      if (e instanceof HttpErrorResponse && e.status === 409) return 'That fiscal year has no periods.';
      if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter the fiscal year as YYYY.';
      return e instanceof HttpErrorResponse ? `Could not roll the year over (${e.status}).` : 'Could not roll the year over.';
    }
  }

  /** Generate the four calendar quarters of a year, named <yyyy>-Q1 to <yyyy>-Q4, leaving out any quarter
   *  whose name is taken or that overlaps a period: how many were made, or an error message when refused. */
  async generateQuarters(year: string): Promise<number | string> {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    const y = year.trim();
    if (!/^\d{4}$/.test(y) || Number(y) < 1) return 'Enter the year as YYYY.';
    const sid = this.session.sheetId()!;
    try {
      const made = await firstValueFrom(
        this.http.post<Period[]>(`${this.url(sid)}/generate-quarters`, { year: Number(y) },
          { headers: { 'X-User': this.session.user()! } }),
      );
      if (this.session.sheetId() === sid) this.periods.update((ps) => [...ps, ...made].sort(byFrom));
      return made.length;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not add periods.';
      if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter the year as YYYY.';
      return e instanceof HttpErrorResponse ? `Could not generate periods (${e.status}).` : 'Could not generate periods.';
    }
  }

  /** The fiscal year (FY<yyyy>) a date (YYYY-MM-DD) falls in, by the sheet's fiscal start month: named by the
   *  calendar year its first day falls in. */
  fiscalYearOf(date: string): string {
    const year = Number(date.slice(0, 4));
    const month = Number(date.slice(5, 7));
    return 'FY' + String(month >= this.session.fiscalStartMonth() ? year : year - 1).padStart(4, '0');
  }

  /** Paste opening balances, lines of name,period,amount, each adding its amount to a person's carried figure
   *  for a period: how many lines went through, or an error message when the paste is refused. The periods
   *  are re-read after it. */
  async importOpenings(text: string): Promise<number | string> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    if (!text.trim()) return 'Paste at least one line of name,period,amount.';
    try {
      const done = await firstValueFrom(
        this.http.post<{ count: number }>(`${this.url(sid)}/openings`, { text }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) await this.load();
      return done.count;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        const message = (e.error as { message?: unknown } | null)?.message;
        return typeof message === 'string' && message ? message : 'A line of the paste is wrong; nothing was added.';
      }
      if (e instanceof HttpErrorResponse && e.status === 400) return 'Paste lines of name,period,amount.';
      return e instanceof HttpErrorResponse ? `Could not paste opening balances (${e.status}).` : 'Could not paste opening balances.';
    }
  }

  /** Close a period, fixing its due-total; an error message when refused. */
  async close(id: number): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const period = await firstValueFrom(
        this.http.post<Period>(`${this.url(sid)}/${id}/close`, null, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.periods.update((ps) => ps.map((p) => (p.id === id ? period : p)));
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not close a period.';
      if (e instanceof HttpErrorResponse && e.status === 409) return 'The period is already closed.';
      return e instanceof HttpErrorResponse ? `Could not close period (${e.status}).` : 'Could not close period.';
    }
  }

  /** Soft-close a period, fixing its figures while its claims can still change; an error message when
   *  refused. */
  async softClose(id: number): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const period = await firstValueFrom(
        this.http.post<Period>(`${this.url(sid)}/${id}/soft-close`, null, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.periods.update((ps) => ps.map((p) => (p.id === id ? period : p)));
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not close a period.';
      if (e instanceof HttpErrorResponse && e.status === 409) return 'The period is already closed.';
      return e instanceof HttpErrorResponse ? `Could not soft-close period (${e.status}).` : 'Could not soft-close period.';
    }
  }

  /** Reopen a closed or soft-closed period, making its due-total live again (a role the sheet allows); an error message when
   *  refused. */
  async reopen(id: number): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const period = await firstValueFrom(
        this.http.post<Period>(`${this.url(sid)}/${id}/reopen`, null, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.periods.update((ps) => ps.map((p) => (p.id === id ? period : p)));
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not reopen a period.';
      if (e instanceof HttpErrorResponse && e.status === 409) return 'The period is not closed, or is locked.';
      return e instanceof HttpErrorResponse ? `Could not reopen period (${e.status}).` : 'Could not reopen period.';
    }
  }

  /** Lock a period so it cannot be reopened, or unlock it (a steward only); an error message when refused. */
  async lock(id: number, locked: boolean): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    const action = locked ? 'lock' : 'unlock';
    try {
      const period = await firstValueFrom(
        this.http.post<Period>(`${this.url(sid)}/${id}/${action}`, null, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.periods.update((ps) => ps.map((p) => (p.id === id ? period : p)));
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 403) return `Only a steward may ${action} a period.`;
      if (e instanceof HttpErrorResponse && e.status === 409) {
        return locked ? 'The period is already locked.' : 'The period is not locked.';
      }
      return e instanceof HttpErrorResponse ? `Could not ${action} period (${e.status}).` : `Could not ${action} period.`;
    }
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/periods`;
  }
}

function byFrom(a: Period, b: Period): number {
  return a.from.localeCompare(b.from);
}
