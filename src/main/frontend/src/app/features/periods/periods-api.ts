import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Period {
  id: number;
  name: string;
  /** The first date (yyyy-mm-dd) of the period, inclusive. */
  from: string;
  /** The last date (yyyy-mm-dd) of the period, inclusive. */
  to: string;
  closed: boolean;
  /** The due-total fixed when the period was closed (two decimals); null while open. */
  closedTotal: string | null;
}

/** The period whose dates contain a date (yyyy-mm-dd), or null when none does. */
export function periodOn(periods: readonly Period[], date: string): Period | null {
  return periods.find((p) => p.from <= date && date <= p.to) ?? null;
}

// The sheet's periods, read and written through the server.
@Injectable({ providedIn: 'root' })
export class PeriodsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Promise<Period[]> {
    return firstValueFrom(this.http.get<Period[]>(this.url(sheetId), { headers: this.headers() }));
  }

  add(sheetId: string, name: string, from: string, to: string): Promise<Period> {
    return firstValueFrom(this.http.post<Period>(this.url(sheetId), { name, from, to }, { headers: this.headers() }));
  }

  close(sheetId: string, id: number): Promise<Period> {
    return firstValueFrom(this.http.post<Period>(`${this.url(sheetId)}/${id}/close`, null, { headers: this.headers() }));
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
  return 'Something went wrong; try again.';
}

export function closeError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The period is already closed.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That period is no longer on this sheet.';
  return 'Something went wrong; try again.';
}
