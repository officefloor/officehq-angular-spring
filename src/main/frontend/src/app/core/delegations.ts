import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** The steward role delegated by a steward to a user from a day to a day (yyyy-mm-dd), inclusive. */
export interface Delegation {
  id: number;
  user: string;
  by: string;
  from: string;
  to: string;
}

// The delegations of the steward role on the active sheet: while the sheet's today is inside one's
// range, its user acts as a steward.
@Injectable({ providedIn: 'root' })
export class Delegations {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The delegations made on the sheet, in the order they were made. */
  list(sheetId: string): Promise<Delegation[]> {
    return firstValueFrom(this.http.get<Delegation[]>(this.url(sheetId)));
  }

  /** Delegate the steward role to a user from a day (null: the sheet's today) to a day (null: the
   *  from-day); refused (403) unless the signed-in user is a steward, (400) when it ends before it
   *  starts. */
  delegate(user: string, from: string | null, to: string | null): Promise<Delegation> {
    const sheetId = this.session.sheetId();
    const by = this.session.user();
    if (!sheetId || !by) throw new Error('no sheet loaded');
    return firstValueFrom(
      this.http.post<Delegation>(this.url(sheetId), { user, from, to }, { headers: { 'X-User': by } }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/delegations`;
  }
}
