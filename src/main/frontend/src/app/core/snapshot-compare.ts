import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

// Compares the active sheet's snapshots of two dates, person by person.
@Injectable({ providedIn: 'root' })
export class SnapshotCompare {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** One line per person in list order, `<name> <then> <now>` (a blank to-date compares with now). An error
   *  message when that failed. */
  async compare(from: string, to: string): Promise<{ lines: string[] } | { error: string }> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    if (!user || !sid) return { error: 'Load a sheet first.' };
    const params: Record<string, string> = {};
    if (from.trim()) params['from'] = from.trim();
    if (to.trim()) params['to'] = to.trim();
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sid)}/people/snapshot-compare`, {
          headers: { 'X-User': user },
          params,
        }),
      );
      return { lines };
    } catch (e) {
      return { error: e instanceof HttpErrorResponse ? `Could not compare (${e.status}).` : 'Could not compare.' };
    }
  }
}
