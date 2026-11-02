import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A person the signed-in user watches, by id. */
export interface Watch {
  id: number;
  personId: number;
}

// The people the signed-in user watches on the active sheet: they are told when a claim of one is
// settled, approved or re-settled.
@Injectable({ providedIn: 'root' })
export class Watches {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The people the signed-in user watches on the sheet, in the order they started watching. */
  list(sheetId: string): Promise<Watch[]> {
    const user = this.session.user();
    if (!user) throw new Error('not signed in');
    return firstValueFrom(
      this.http.get<Watch[]>(`/api/sheets/${encodeURIComponent(sheetId)}/watches`, { headers: { 'X-User': user } }),
    );
  }

  /** Start watching the person; refused when the user already does. */
  watch(personId: number): Promise<Watch> {
    const { url, user } = this.active(personId);
    return firstValueFrom(this.http.post<Watch>(url, null, { headers: { 'X-User': user } }));
  }

  /** Stop watching the person; refused when the user does not. */
  unwatch(personId: number): Promise<void> {
    const { url, user } = this.active(personId);
    return firstValueFrom(this.http.delete<void>(url, { headers: { 'X-User': user } }));
  }

  private active(personId: number): { url: string; user: string } {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return { url: `/api/sheets/${encodeURIComponent(sheetId)}/people/${personId}/watch`, user };
  }
}
