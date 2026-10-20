import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Session } from '../../core/session';

export interface Period {
  id: number;
  /** Unique on the sheet. */
  name: string;
  /** yyyy-mm-dd, both inclusive. */
  from: string;
  to: string;
  /** Closed, soft or not: its figures are fixed. */
  closed: boolean;
  /** Soft-closed: closed, but claims dated in it can still be added and changed. */
  soft: boolean;
  /** The due-total fixed when it closed; null while open. */
  closedTotal: string | null;
  /** Each person's due-total for it, by person id, fixed when it closed (a person with none has 0.00); null
   * while open. */
  personTotals: Record<string, string> | null;
}

export interface PeriodForm {
  name: string;
  from: string;
  to: string;
}

/** The period whose dates contain a date (yyyy-mm-dd); none when no period does. */
export function periodOf(periods: readonly Period[], date: string): Period | undefined {
  return periods.find((p) => p.from <= date && date <= p.to);
}

// The periods of the active sheet, read, added, closed and reopened through the server.
@Injectable({ providedIn: 'root' })
export class PeriodsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Observable<Period[]> {
    return this.http.get<Period[]>(this.url(sheetId), { headers: this.headers() });
  }

  add(sheetId: string, form: PeriodForm): Observable<Period> {
    return this.http.post<Period>(this.url(sheetId), form, { headers: this.headers() });
  }

  /** One period per calendar month from the from-date's month to the to-date's; the ones made, by from-date. */
  generate(sheetId: string, from: string, to: string): Observable<Period[]> {
    return this.http.post<Period[]>(`${this.url(sheetId)}/generate`, { from, to }, { headers: this.headers() });
  }

  close(sheetId: string, id: number): Observable<Period> {
    return this.http.post<Period>(`${this.url(sheetId)}/${id}/close`, null, { headers: this.headers() });
  }

  softClose(sheetId: string, id: number): Observable<Period> {
    return this.http.post<Period>(`${this.url(sheetId)}/${id}/soft-close`, null, { headers: this.headers() });
  }

  reopen(sheetId: string, id: number): Observable<Period> {
    return this.http.post<Period>(`${this.url(sheetId)}/${id}/reopen`, null, { headers: this.headers() });
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/periods`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}
