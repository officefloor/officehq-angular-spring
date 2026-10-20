import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Session } from '../../core/session';

/** A steward's delegation of the steward role to a user, from a date to a date (yyyy-mm-dd), inclusive. */
export interface Delegation {
  id: number;
  /** The steward who delegated. */
  by: string;
  user: string;
  from: string;
  to: string;
}

// A sheet's delegations of the steward role, read and made through the server.
@Injectable({ providedIn: 'root' })
export class DelegationsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Observable<Delegation[]> {
    return this.http.get<Delegation[]>(this.url(sheetId), { headers: this.headers() });
  }

  delegate(sheetId: string, f: { user: string; from: string; to: string }): Observable<Delegation> {
    return this.http.post<Delegation>(this.url(sheetId), f, { headers: this.headers() });
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/delegations`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}
