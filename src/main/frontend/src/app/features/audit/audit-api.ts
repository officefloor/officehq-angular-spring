import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// The audit lines of a sheet, read from the audit file as it holds them.
@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly http = inject(HttpClient);

  /** The sheet's audit lines newest first; with a name, only those about that person. */
  lines(sheetId: string, name: string): Promise<string[]> {
    return firstValueFrom(
      this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheetId)}/audit`, { params: { name } }),
    );
  }

  /** The sheet's activity: one line "<user> <EVENT> <count>" per user and event, sorted by user then event. */
  activity(sheetId: string): Promise<string[]> {
    return firstValueFrom(this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheetId)}/audit/activity`));
  }
}
