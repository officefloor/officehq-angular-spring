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
}

// The consolidated figures, read from the store.
@Injectable({ providedIn: 'root' })
export class ConsolidatedApi {
  private readonly http = inject(HttpClient);

  get(sheet: string): Promise<ConsolidatedView> {
    return firstValueFrom(this.http.get<ConsolidatedView>(`/api/sheets/${encodeURIComponent(sheet)}/consolidated`));
  }
}
