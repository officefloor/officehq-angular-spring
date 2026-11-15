import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's audit lines, read from the audit file through the server.
@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's audit lines as the file holds them, newest first; with a name, only those about
   *  that person. */
  lines(sheetId: string, name: string): Promise<string[]> {
    return firstValueFrom(this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheetId)}/audit`, {
      params: { name },
      headers: { 'X-User': this.session.user() ?? '' },
    }));
  }

  /** The sheet's activity lines, `<user> <EVENT> <count>`, sorted by user then event. */
  activity(sheetId: string): Promise<string[]> {
    return firstValueFrom(this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheetId)}/activity`, {
      headers: { 'X-User': this.session.user() ?? '' },
    }));
  }
}
