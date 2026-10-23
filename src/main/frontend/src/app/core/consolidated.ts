import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/**
 * A sheet's figures over it and all its descendants (the sheets naming it as their parent, theirs, and
 * so on), each sheet's as that sheet shows them now, or as it stood at the end of the as-of date while one
 * is set, with that date as its today.
 */
export interface ConsolidatedView {
  peopleCount: number;
  /** The sheets' due-totals added up, with two decimals. */
  dueTotal: string;
  /** The sheets' overdue-totals added up, with two decimals. */
  overdueTotal: string;
  /** The people's settled-totals over the tree added up, trimmed once, with two decimals. */
  settledTotal: string;
  /** The people's open-totals over the tree added up, trimmed once, with two decimals. */
  openTotal: string;
  /** The people's cut totals over the tree added up, trimmed once, with two decimals. */
  cutTotal: string;
  /**
   * Per grade, the due-totals of the people not retired who have it in force on their sheet's today
   * added up, with two decimals; a grade nobody has is left out.
   */
  gradeDueTotals: Record<string, string>;
  /** Per status, how many claims have it; a status no claim has is left out. */
  statusCounts: Record<string, number>;
}

/** A sheet's own summary due-total per grade, with two decimals (a grade nobody has left out); not found when no sheet has the id. */
export interface GradeDueTotalsView {
  found: boolean;
  gradeDueTotals: Record<string, string>;
}

// The consolidated figures, read from the store.
@Injectable({ providedIn: 'root' })
export class ConsolidatedApi {
  private readonly http = inject(HttpClient);

  get(sheet: string): Promise<ConsolidatedView> {
    return firstValueFrom(this.http.get<ConsolidatedView>(`/api/sheets/${encodeURIComponent(sheet)}/consolidated`));
  }

  /** Another sheet's own summary due-totals per grade, as it stands now. */
  gradeDueTotals(sheet: string): Promise<GradeDueTotalsView> {
    return firstValueFrom(
      this.http.get<GradeDueTotalsView>(`/api/sheets/${encodeURIComponent(sheet)}/grade-due-totals`),
    );
  }
}
