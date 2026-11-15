import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A person found: the sheet they are on, their name, their due-total as the lists show it and
 *  their grade in force on that sheet's today. */
export interface Found {
  sheetId: string;
  name: string;
  dueTotal: string;
  grade: string;
}

// People found by name or alias over a sheet's tree.
@Injectable({ providedIn: 'root' })
export class SearchApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** Everyone on the sheet and the other sheets of its tree whose name or an alias contains the
   *  text (ignoring case): the sheet's own first in list order, then the others' by sheet id. */
  search(sheetId: string, text: string): Promise<Found[]> {
    return firstValueFrom(this.http.get<Found[]>(`/api/sheets/${encodeURIComponent(sheetId)}/search`, {
      params: { text },
      headers: { 'X-User': this.session.user() ?? '' },
    }));
  }
}
