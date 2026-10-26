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
  /** Per grade, the due-totals of the descendants' people on it today who are not retired, added up, with
   *  two decimals. */
  gradeDueTotals: Record<string, string>;
  /** Per status, how many of the descendants' claims have it. */
  statusCounts: Record<string, number>;
  /** Each of the descendants' people: their name and aliases as names are compared, and their figures. */
  people: TreePersonFigures[];
}

/** A person's figures on a sheet of the tree: their name and aliases as names are compared (trimmed, lower-cased),
 *  their due-total with two decimals (null for n/a) and their overdue-total with two decimals. */
export interface TreePersonFigures {
  keys: string[];
  dueTotal: string | null;
  overdueTotal: string;
}

// The figures of a sheet's descendants, and its own: while the screen shows the sheet as of a date, every
// sheet of the tree as it stood at the end of that day.
@Injectable({ providedIn: 'root' })
export class ConsolidatedApi {
  private readonly http = inject(HttpClient);

  descendants(sheetId: string): Promise<DescendantsView> {
    return firstValueFrom(this.http.get<DescendantsView>(`/api/sheets/${encodeURIComponent(sheetId)}/descendants`));
  }
}

/** A person found over a sheet's tree: their sheet, name and due-total (two decimals, or n/a). */
export interface TreeSearchHit {
  sheetId: string;
  name: string;
  dueTotal: string;
}

/** A person of a sheet's tree: their sheet, name, grade in force on their sheet's today and due-total
 *  (two decimals, or n/a). */
export interface TreePerson {
  sheetId: string;
  name: string;
  grade: string;
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

  /** Every person over the sheet's tree, in the same order as a search. */
  everyone(sheetId: string): Promise<TreePerson[]> {
    return firstValueFrom(this.http.get<TreePerson[]>(`/api/sheets/${encodeURIComponent(sheetId)}/tree-people`,
      { context: new HttpContext().set(LIVE_READ, true) }));
  }
}

// Per grade, a sheet's summary due-total with two decimals; none for a sheet that does not exist. Read
// live, as the store holds it now.
@Injectable({ providedIn: 'root' })
export class CompareSheetApi {
  private readonly http = inject(HttpClient);

  gradeDueTotals(sheetId: string): Promise<Record<string, string>> {
    return firstValueFrom(this.http.get<Record<string, string>>(`/api/sheets/${encodeURIComponent(sheetId)}/grade-due-totals`,
      { context: new HttpContext().set(LIVE_READ, true) }));
  }
}
