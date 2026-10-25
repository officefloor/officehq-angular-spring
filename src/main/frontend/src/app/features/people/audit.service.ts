import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The audit lines of a sheet, read from the server as the audit file holds them, newest first.
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's audit lines; with a name (not blank), only those about that person. */
  lines(sheet: string, name: string): Promise<string[]> {
    return firstValueFrom(
      this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/audit`, {
        params: { name },
        headers: this.session.headers(),
      }),
    );
  }

  /** The claim's timeline: the sheet's lines about it, newest first. */
  claimLines(sheet: string, claimId: number): Promise<string[]> {
    return firstValueFrom(
      this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/audit/claims/${claimId}`, {
        headers: this.session.headers(),
      }),
    );
  }

  /** The sheet's activity: one line `<user> <EVENT> <count>` per user and event, by user then event. */
  activity(sheet: string): Promise<string[]> {
    return firstValueFrom(
      this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/audit/activity`, {
        headers: this.session.headers(),
      }),
    );
  }
}
