import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A person found by a tree search: their sheet's id, name and due-total (two decimals, or n/a). */
export interface Found {
  sheet: string;
  name: string;
  dueTotal: string;
}

/** A person of a sheet's tree as the tree export lists them: their sheet's id, name, grade and
 *  due-total (two decimals, or n/a). */
export interface TreePerson {
  sheet: string;
  name: string;
  grade: string;
  dueTotal: string;
}

// The search over a sheet and every other sheet of its tree (its ancestors and descendants).
@Injectable({ providedIn: 'root' })
export class TreeSearch {
  private readonly http = inject(HttpClient);

  /** Everyone on the sheet's tree whose name or an alias contains the text, case ignored: the
   *  sheet's people first in list order, then the other sheets' by sheet id. */
  search(sheetId: string, text: string): Promise<Found[]> {
    return firstValueFrom(
      this.http.get<Found[]>(`/api/sheets/${encodeURIComponent(sheetId)}/search`, { params: { text } }),
    );
  }

  /** Everyone on the sheet's tree: the sheet's people first in list order, then the other sheets'
   *  by sheet id. */
  people(sheetId: string): Promise<TreePerson[]> {
    return firstValueFrom(this.http.get<TreePerson[]>(`/api/sheets/${encodeURIComponent(sheetId)}/tree-people`));
  }
}
