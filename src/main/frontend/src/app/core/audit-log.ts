import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// The audit file as the viewer reads it: the lines of a sheet, newest first.
@Injectable({ providedIn: 'root' })
export class AuditLog {
  private readonly http = inject(HttpClient);

  /** The sheet's audit lines exactly as the file holds them, newest first; with a name, only
   *  those carrying name= or person= of that person. */
  lines(sheetId: string, name: string): Promise<string[]> {
    const params: Record<string, string> = name.trim() ? { name } : {};
    return firstValueFrom(this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheetId)}/audit`, { params }));
  }

  /** A claim's timeline: the sheet's audit lines about the claim exactly as the file holds them,
   *  newest first. */
  timeline(sheetId: string, claimId: number): Promise<string[]> {
    return firstValueFrom(
      this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheetId)}/audit/claims/${claimId}`));
  }

  /** The sheet's activity: one line `<user> <EVENT> <count>` for every user and event in its
   *  audit lines, sorted by user then event. */
  activity(sheetId: string): Promise<string[]> {
    return firstValueFrom(this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheetId)}/audit/activity`));
  }
}
