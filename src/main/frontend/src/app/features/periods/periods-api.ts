import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, otherError } from '../../core/session';
import { cents } from '../claims/claims-api';

export interface Period {
  id: number;
  name: string;
  /** The first date (yyyy-mm-dd) of the period, inclusive. */
  from: string;
  /** The last date (yyyy-mm-dd) of the period, inclusive. */
  to: string;
  closed: boolean;
  /** Whether the period is soft-closed: its figures fixed, its claims still open to change. */
  softClosed: boolean;
  /** The due-total fixed when the period was closed (two decimals); null while open. */
  closedTotal: string | null;
  /** The most OPEN or PENDING claims a person may have in the period; null for no cap. */
  cap: number | null;
  /** Each person's due-total for the period fixed when it was closed (two decimals), by person
   *  id; empty while open. */
  closedPersonTotals: Record<string, string>;
  /** Each person's carried figure for the period fixed when it was closed (two decimals), by
   *  person id; null while open, or when it was closed before carried figures were fixed. */
  closedPersonCarried: Record<string, string> | null;
  /** Each person's opening balance for the period (two decimals), by person id: added to the
   *  carried figure they show for it while it is open. */
  openings: Record<string, string>;
  /** Whether a steward has locked the period: a locked one cannot be reopened. */
  locked: boolean;
}

/** What an opening-balance paste did: how many lines went through, and the periods it changed. */
export interface OpeningsImported {
  imported: number;
  periods: Period[];
}

/** What a rollover did: how many periods it made, and the periods it made or carried openings into. */
export interface RolledOver {
  made: number;
  periods: Period[];
}

/** The period whose dates contain a date (yyyy-mm-dd), or null when none does. */
export function periodOn(periods: readonly Period[], date: string): Period | null {
  return periods.find((p) => p.from <= date && date <= p.to) ?? null;
}

/** The due-total a period shows, in cents: the sum of the people's due-totals for it, from
 *  dueTotals (by period id; 0 with none). */
export function periodDueCents(p: Period, dueTotals: ReadonlyMap<number, number>): number {
  return dueTotals.get(p.id) ?? 0;
}

/** The due-total a person shows for a period, in cents: the one fixed when it was closed or
 *  soft-closed (0 for a person added since), else the live one from dueTotals (by period id; 0 with none). */
export function personPeriodDueCents(p: Period, personId: number, dueTotals: ReadonlyMap<number, number>): number {
  if (p.closed || p.softClosed) {
    const fixed = p.closedPersonTotals?.[personId];
    return fixed === undefined ? 0 : cents(fixed);
  }
  return dueTotals.get(p.id) ?? 0;
}

/** The carried figure a person shows for a period, in cents: the one fixed when it was closed or
 *  soft-closed (0 for a person added since), else the live one from carried (by period id; 0 with none). */
export function personPeriodCarriedCents(p: Period, personId: number, carried: ReadonlyMap<number, number>): number {
  if ((p.closed || p.softClosed) && p.closedPersonCarried !== null) {
    const fixed = p.closedPersonCarried[personId];
    return fixed === undefined ? 0 : cents(fixed);
  }
  return carried.get(p.id) ?? 0;
}

// The sheet's periods, read and written through the server.
@Injectable({ providedIn: 'root' })
export class PeriodsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Promise<Period[]> {
    return firstValueFrom(this.http.get<Period[]>(this.url(sheetId), { headers: this.headers() }));
  }

  add(sheetId: string, name: string, from: string, to: string, cap: number | null): Promise<Period> {
    return firstValueFrom(this.http.post<Period>(this.url(sheetId), { name, from, to, cap }, { headers: this.headers() }));
  }

  /** Make one period per calendar month from the from-date's month to the to-date's; returns
   *  the periods made, months already taken or overlapping left out. */
  generate(sheetId: string, from: string, to: string): Promise<Period[]> {
    return firstValueFrom(this.http.post<Period[]>(`${this.url(sheetId)}/generate`, { from, to }, { headers: this.headers() }));
  }

  /** Make the twelve monthly periods of the fiscal year named by its first day's calendar year,
   *  as {@link generate} does; returns the periods made. */
  generateFiscalYear(sheetId: string, year: string): Promise<Period[]> {
    return firstValueFrom(this.http.post<Period[]>(`${this.url(sheetId)}/generate-fy`, { year }, { headers: this.headers() }));
  }

  /** Make the four calendar quarters of a year, named <yyyy>-Q1 to <yyyy>-Q4, leaving out one
   *  whose name is in use or whose days overlap a period; returns the periods made. */
  generateQuarters(sheetId: string, year: string): Promise<Period[]> {
    return firstValueFrom(this.http.post<Period[]>(`${this.url(sheetId)}/generate-quarters`, { year }, { headers: this.headers() }));
  }

  /** Roll a fiscal year over (a steward only): make the next fiscal year's periods and carry each
   *  person's open figure at the old year's end into the new year's first period. */
  rollover(sheetId: string, year: string): Promise<RolledOver> {
    return firstValueFrom(this.http.post<RolledOver>(`${this.url(sheetId)}/rollover`, { year }, { headers: this.headers() }));
  }

  /** Undo the sheet's last rollover (its steward only, on the sheet-today it was made): remove the
   *  periods it made and take back the openings it carried; returns the sheet's periods after it. */
  undoRollover(sheetId: string): Promise<Period[]> {
    return firstValueFrom(this.http.post<Period[]>(`${this.url(sheetId)}/rollover/undo`, null, { headers: this.headers() }));
  }

  /** Add each pasted line of name,period,amount to the person's opening balance for the period;
   *  all or none go through. */
  importOpenings(sheetId: string, text: string): Promise<OpeningsImported> {
    return firstValueFrom(this.http.post<OpeningsImported>(`${this.url(sheetId)}/openings`, { text }, { headers: this.headers() }));
  }

  close(sheetId: string, id: number): Promise<Period> {
    return firstValueFrom(this.http.post<Period>(`${this.url(sheetId)}/${id}/close`, null, { headers: this.headers() }));
  }

  /** Fix a period's figures while its claims can still be changed. */
  softClose(sheetId: string, id: number): Promise<Period> {
    return firstValueFrom(this.http.post<Period>(`${this.url(sheetId)}/${id}/soft-close`, null, { headers: this.headers() }));
  }

  reopen(sheetId: string, id: number): Promise<Period> {
    return firstValueFrom(this.http.post<Period>(`${this.url(sheetId)}/${id}/reopen`, null, { headers: this.headers() }));
  }

  /** Lock a period so it cannot be reopened (a steward only). */
  lock(sheetId: string, id: number): Promise<Period> {
    return firstValueFrom(this.http.post<Period>(`${this.url(sheetId)}/${id}/lock`, null, { headers: this.headers() }));
  }

  unlock(sheetId: string, id: number): Promise<Period> {
    return firstValueFrom(this.http.post<Period>(`${this.url(sheetId)}/${id}/unlock`, null, { headers: this.headers() }));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/periods`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export function periodError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a name without spaces and a from-date on or before the to-date, as yyyy-mm-dd.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The name is in use on the sheet, or the dates overlap another period.';
  return otherError(e);
}

export function generateError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a from-date on or before the to-date, as yyyy-mm-dd.';
  return otherError(e);
}

export function generateFiscalYearError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a fiscal year as yyyy.';
  return otherError(e);
}

export function generateQuartersError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a year as yyyy.';
  return otherError(e);
}

export function rolloverError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a fiscal year as yyyy.';
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward may roll the year over.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That fiscal year has no periods on the sheet.';
  return otherError(e);
}

export function rolloverUndoError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only the steward who rolled the year over may undo it.';
  if (e instanceof HttpErrorResponse && e.status === 409) {
    return 'The rollover cannot be undone: there is none to undo, it was made on another day, or a claim now belongs to a period it made.';
  }
  return otherError(e);
}

export function openingsError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Give each line as name,period,amount, the amount with two decimals, 0.00 or more.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'A line names a person or period not on the sheet.';
  return otherError(e);
}

export function closeError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not close a period.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The period is already closed.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That period is no longer on this sheet.';
  return otherError(e);
}

export function reopenError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not reopen a period.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The period is neither closed nor soft-closed, or it is locked.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That period is no longer on this sheet.';
  return otherError(e);
}

export function lockError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward may lock or unlock a period.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The period is already locked, or not locked.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That period is no longer on this sheet.';
  return otherError(e);
}
