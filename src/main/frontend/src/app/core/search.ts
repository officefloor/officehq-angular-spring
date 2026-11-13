import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A person found over the sheet's tree: their sheet, name and due-total (n/a when they have none). */
export interface Found {
  sheetId: string;
  name: string;
  dueTotal: string;
}

// Finds people over the active sheet and every other sheet of its tree, by name or alias.
@Injectable({ providedIn: 'root' })
export class TreeSearch {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The people whose name or an alias contains the text, case ignored. An error message when that failed. */
  async find(text: string): Promise<{ found: Found[] } | { error: string }> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    if (!user || !sid) return { error: 'Load a sheet first.' };
    try {
      const found = await firstValueFrom(
        this.http.get<Found[]>(`/api/sheets/${encodeURIComponent(sid)}/search`, {
          headers: { 'X-User': user },
          params: { text: text.trim() },
        }),
      );
      return { found };
    } catch (e) {
      return { error: e instanceof HttpErrorResponse ? `Could not search (${e.status}).` : 'Could not search.' };
    }
  }
}
