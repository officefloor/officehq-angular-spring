import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { LIVE_READ } from '../../core/as-of.interceptor';

/** A sheet's descendants (its children, then theirs, and so on) and their figures added up. */
export interface DescendantsView {
  sheets: string[];
  peopleCount: number;
  /** Each descendant's due-total as that sheet shows it, added up, with two decimals. */
  dueTotal: string;
  /** Each descendant's overdue-total as that sheet shows it, added up, with two decimals. */
  overdueTotal: string;
  /** The sheet's own figures as it shows them now. */
  ownPeopleCount: number;
  ownDueTotal: string;
  ownOverdueTotal: string;
}

// The figures of a sheet's descendants, and its own, as the store holds them now: read live even while
// the screen shows the sheet as of a date.
@Injectable({ providedIn: 'root' })
export class ConsolidatedApi {
  private readonly http = inject(HttpClient);

  descendants(sheetId: string): Promise<DescendantsView> {
    return firstValueFrom(this.http.get<DescendantsView>(`/api/sheets/${encodeURIComponent(sheetId)}/descendants`,
      { context: new HttpContext().set(LIVE_READ, true) }));
  }
}

/** A person found over a sheet's tree: their sheet, name and due-total (two decimals, or n/a). */
export interface TreeSearchHit {
  sheetId: string;
  name: string;
  dueTotal: string;
}

// The people over a sheet's tree whose name or an alias contains the text, case ignored: the sheet's
// own first in list order, then the other sheets' by sheet id. Read live, as the store holds them now.
@Injectable({ providedIn: 'root' })
export class TreeSearchApi {
  private readonly http = inject(HttpClient);

  search(sheetId: string, text: string): Promise<TreeSearchHit[]> {
    return firstValueFrom(this.http.get<TreeSearchHit[]>(`/api/sheets/${encodeURIComponent(sheetId)}/tree-search`,
      { params: { text }, context: new HttpContext().set(LIVE_READ, true) }));
  }
}
