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
  /** The due-total fixed when it was closed, two decimals; null while open. */
  closedTotal: string | null;
  /** Each person's due-total for it fixed when it was closed, two decimals, by person id; empty while
   *  open. A person missing here (added after it closed) has 0.00. */
  closedPersonTotals: Record<number, string>;
}

export interface PeriodFields {
  name: string;
  /** Blank takes the sheet's today. */
  from: string;
  to: string;
}

// The active sheet's periods, by from-date, re-read from the server each time the sheet is loaded.
// No two overlap, so a date falls in at most one of them.
@Injectable({ providedIn: 'root' })
export class Periods {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly periods = signal<Period[]>([]);
  private loads = 0;

  /** The period whose dates contain a date (YYYY-MM-DD), or undefined when none does. */
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
    if (this.periods().some((p) => p.name.toLowerCase() === name.toLowerCase())) return 'That period name is already in use.';
    const clash = this.periods().find((p) => p.from <= to && from <= p.to);
    if (clash) return `The period overlaps ${clash.name}.`;
    const sid = this.session.sheetId()!;
    try {
      const period = await firstValueFrom(
        this.http.post<Period>(this.url(sid), { name, from, to }, { headers: { 'X-User': this.session.user()! } }),
      );
      if (this.session.sheetId() === sid) this.periods.update((ps) => [...ps, period].sort(byFrom));
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        return 'The period overlaps another, or its name is already in use.';
      }
      if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a name, and the from and to dates as YYYY-MM-DD.';
      return e instanceof HttpErrorResponse ? `Could not add period (${e.status}).` : 'Could not add period.';
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
      if (e instanceof HttpErrorResponse && e.status === 409) return 'The period is already closed.';
      return e instanceof HttpErrorResponse ? `Could not close period (${e.status}).` : 'Could not close period.';
    }
  }

  /** Reopen a closed period, making its due-total live again (a steward only); an error message when
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
      if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward can reopen a period.';
      if (e instanceof HttpErrorResponse && e.status === 409) return 'The period is not closed.';
      return e instanceof HttpErrorResponse ? `Could not reopen period (${e.status}).` : 'Could not reopen period.';
    }
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/periods`;
  }
}

function byFrom(a: Period, b: Period): number {
  return a.from.localeCompare(b.from);
}
