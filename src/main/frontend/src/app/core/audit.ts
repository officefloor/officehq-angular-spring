import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

// The active sheet's audit lines, read back from the audit file as it holds them now.
@Injectable({ providedIn: 'root' })
export class AuditLog {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's audit lines, newest first; with a name, only those about that person. An error message when that failed. */
  async lines(name: string): Promise<{ lines: string[] } | { error: string }> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    if (!user || !sid) return { error: 'Load a sheet first.' };
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sid)}/audit`, {
          headers: { 'X-User': user },
          params: { name: name.trim() },
        }),
      );
      return { lines };
    } catch (e) {
      return { error: e instanceof HttpErrorResponse ? `Could not read the audit (${e.status}).` : 'Could not read the audit.' };
    }
  }
}
