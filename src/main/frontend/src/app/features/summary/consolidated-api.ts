import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A sheet's descendants (its children, then theirs, and so on) and their figures added up. */
export interface DescendantsView {
  sheets: string[];
  peopleCount: number;
  /** Each descendant's due-total as that sheet shows it, added up, with two decimals. */
  dueTotal: string;
  /** Each descendant's overdue-total as that sheet shows it, added up, with two decimals. */
  overdueTotal: string;
}

// The figures of a sheet's descendants, as the store holds them.
@Injectable({ providedIn: 'root' })
export class ConsolidatedApi {
  private readonly http = inject(HttpClient);

  descendants(sheetId: string): Promise<DescendantsView> {
    return firstValueFrom(this.http.get<DescendantsView>(`/api/sheets/${encodeURIComponent(sheetId)}/descendants`));
  }
}
