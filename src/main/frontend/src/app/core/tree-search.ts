import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A person found over a sheet's tree: their sheet, name, grade in force on their sheet's today and due-total (two decimals, or n/a). */
export interface FoundView {
  sheetId: string;
  name: string;
  grade: string;
  dueTotal: string;
}

// The people of a sheet's whole tree, all or those whose name or an alias contains a text, read from the store.
@Injectable({ providedIn: 'root' })
export class TreeSearchApi {
  private readonly http = inject(HttpClient);

  search(sheet: string, text: string): Promise<FoundView[]> {
    return firstValueFrom(
      this.http.get<FoundView[]>(`/api/sheets/${encodeURIComponent(sheet)}/search`, { params: { text } }),
    );
  }

  /** Every person of the sheet's whole tree: this sheet's first in list order, then the other sheets' by sheet id. */
  everyone(sheet: string): Promise<FoundView[]> {
    return firstValueFrom(this.http.get<FoundView[]>(`/api/sheets/${encodeURIComponent(sheet)}/tree-people`));
  }
}
