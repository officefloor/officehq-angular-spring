import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** A sheet's descendants' figures added up (not the sheet's own): how many people, and each descendant's
 *  due-total and overdue-total as that sheet shows it (two decimals). */
export interface DescendantFigures {
  peopleCount: number;
  dueTotal: string;
  overdueTotal: string;
}

/** No descendants: nothing to add to a sheet's own figures. */
export const NO_DESCENDANTS: DescendantFigures = { peopleCount: 0, dueTotal: '0.00', overdueTotal: '0.00' };

// A sheet's descendants (its children, the sheets naming it as their parent, and theirs and so on), whose figures
// the screen adds to the sheet's own to show them consolidated.
@Injectable({ providedIn: 'root' })
export class Consolidated {
  private readonly http = inject(HttpClient);

  /** With `self`, the sheet's own live figures are added in too. */
  descendants(sheetId: string, self = false): Observable<DescendantFigures> {
    return this.http.get<DescendantFigures>(`/api/sheets/${encodeURIComponent(sheetId)}/descendants/figures`,
      self ? { params: { self: 'true' } } : {});
  }
}
