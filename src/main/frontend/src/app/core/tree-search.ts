import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** A person found on a sheet's tree: the sheet they are on, their name, their due-total as that sheet shows it, and
 *  their grade in force on that sheet's today. */
export interface TreeFound {
  sheet: string;
  name: string;
  dueTotal: string;
  grade: string;
}

// A search over a sheet's tree: the sheet at the top of its line of parents and all of that sheet's descendants.
@Injectable({ providedIn: 'root' })
export class TreeSearch {
  private readonly http = inject(HttpClient);

  /** Everyone on the tree whose name or an alias contains the text, case ignored: the sheet's own people first in
   *  list order, then the other sheets' by sheet id. */
  search(sheetId: string, text: string): Observable<TreeFound[]> {
    return this.http.get<TreeFound[]>(`/api/sheets/${encodeURIComponent(sheetId)}/tree/search`,
      { params: { text } });
  }
}
