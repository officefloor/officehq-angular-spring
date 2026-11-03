import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A sheet's descendants' figures added up: their people, and each one's due-total and
 *  overdue-total as that sheet shows them; per grade, the due-totals of the people not retired on
 *  it (n/a skipped); and per status, how many claims have it. A grade or status missing has none. */
export interface DescendantFigures {
  peopleCount: number;
  dueTotal: string;
  overdueTotal: string;
  gradeDueTotals: Record<string, string>;
  statusCounts: Record<string, number>;
}

/** No descendants: nothing to add. */
export const NO_DESCENDANTS: DescendantFigures = {
  peopleCount: 0,
  dueTotal: '0.00',
  overdueTotal: '0.00',
  gradeDueTotals: {},
  statusCounts: {},
};

// The sheets below a sheet: its children (the sheets naming it as their parent), theirs, and so on.
@Injectable({ providedIn: 'root' })
export class Descendants {
  private readonly http = inject(HttpClient);

  /** The figures of the sheet's descendants added up; while an as-of date is set, each as it stood
   *  at the end of that day. */
  figures(sheetId: string): Promise<DescendantFigures> {
    return firstValueFrom(this.http.get<DescendantFigures>(`/api/sheets/${encodeURIComponent(sheetId)}/descendants`));
  }
}
