import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ClaimView } from './claims';

/** What a person's statements come to: how many, and the last one's due-total and date (yyyy-mm-dd). */
export interface StatementSummary {
  personId: number;
  count: number;
  /** The due-total with exactly two decimals, or "n/a" when the grade had no rate then. */
  dueTotal: string | null;
  date: string | null;
}

/** A statement just taken: the person's summary and their claims as it left them, newest first. */
export interface StatementTaken {
  summary: StatementSummary;
  claims: ClaimView[];
}

// The statements taken of people on a sheet. Taking one binds each of the person's claims that
// count to the figure it has then, and unbinds the rest.
@Injectable({ providedIn: 'root' })
export class Statements {
  private readonly http = inject(HttpClient);

  list(sheetId: string): Observable<StatementSummary[]> {
    return this.http.get<StatementSummary[]>(`${this.base(sheetId)}/statements`);
  }

  /** Take a statement of the person now, or as of a date (yyyy-mm-dd) on or before the sheet's today. */
  take(sheetId: string, personId: number, asOf: string | null = null): Observable<StatementTaken> {
    return this.http.post<StatementTaken>(`${this.base(sheetId)}/people/${personId}/statements`, null,
      asOf ? { params: { asOf } } : {});
  }

  /** Cancel the person's last statement: its claims are live again; the summary is of the statement before it. */
  cancel(sheetId: string, personId: number): Observable<StatementTaken> {
    return this.http.delete<StatementTaken>(`${this.base(sheetId)}/people/${personId}/statements/last`);
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }
}
