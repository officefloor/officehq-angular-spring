import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** The steward role delegated by a steward (the delegator) to a user, from a from-date to a to-date
 *  (YYYY-MM-DD), inclusive: while the sheet's today is inside it the user acts as a steward. */
export interface Delegation {
  id: number;
  user: string;
  delegator: string;
  from: string;
  to: string;
}

// The delegations of the steward role on a sheet, read from and written to the server.
@Injectable({ providedIn: 'root' })
export class DelegationsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheet: string): Promise<Delegation[]> {
    return firstValueFrom(this.http.get<Delegation[]>(this.url(sheet), { headers: this.session.headers() }));
  }

  /** Delegate the steward role; a null from is the sheet's today. Refused (409) unless the user is a
   *  steward, and when the to-date is before the from-date. Resolves to every delegation on the sheet. */
  delegate(sheet: string, user: string, from: string | null, to: string): Promise<Delegation[]> {
    return firstValueFrom(
      this.http.post<Delegation[]>(this.url(sheet), { user, from, to }, { headers: this.session.headers() }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/delegations`;
  }
}
