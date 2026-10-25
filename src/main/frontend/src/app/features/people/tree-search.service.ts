import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A person found over a sheet's tree: their sheet, name and due-total as that sheet shows it, and the
 *  grade in force on that sheet's today. */
export interface Found {
  sheet: string;
  name: string;
  dueTotal: string;
  grade: string | null;
}

// The people of a sheet's tree whose name or an alias contains some text, read from the server.
@Injectable({ providedIn: 'root' })
export class TreeSearchService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The people of the sheet and the other sheets of its tree whose name or an alias contains `text`
   *  (case ignored): the sheet's own first in list order, then the others' by sheet id. */
  search(sheet: string, text: string): Promise<Found[]> {
    return firstValueFrom(
      this.http.get<Found[]>(`/api/sheets/${encodeURIComponent(sheet)}/search`, {
        params: { q: text },
        headers: this.session.headers(),
      }),
    );
  }
}
