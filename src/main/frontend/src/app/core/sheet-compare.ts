import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

// Reads another sheet's summary due-totals by grade, for comparing the active sheet with it.
@Injectable({ providedIn: 'root' })
export class SheetCompare {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The other sheet's due-totals (two decimals) by grade, a grade with no one on it absent; null when that
   *  sheet does not exist. An error message when the read failed. */
  async gradeDueTotals(other: string): Promise<{ totals: Record<string, string> | null } | { error: string }> {
    const user = this.session.user();
    if (!user || !this.session.sheetId()) return { error: 'Load a sheet first.' };
    const sid = other.trim();
    if (!sid) return { error: 'Enter a sheet id.' };
    try {
      const totals = await firstValueFrom(
        this.http.get<Record<string, string>>(`/api/sheets/${encodeURIComponent(sid)}/grade-due-totals`, {
          headers: { 'X-User': user },
        }),
      );
      return { totals };
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 404) return { totals: null };
      return { error: e instanceof HttpErrorResponse ? `Could not compare (${e.status}).` : 'Could not compare.' };
    }
  }
}
