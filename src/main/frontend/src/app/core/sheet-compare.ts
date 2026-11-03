import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** Another sheet's figures to compare a sheet with: whether it exists, and per grade the due-totals
 *  of its people not retired on it (n/a skipped), added up. A grade missing has none. */
export interface Comparison {
  found: boolean;
  gradeDueTotals: Record<string, string>;
}

// The figures of another sheet, for comparing a sheet with it.
@Injectable({ providedIn: 'root' })
export class SheetCompare {
  private readonly http = inject(HttpClient);

  /** The other sheet's per-grade due-totals as it stands now. */
  compare(sheetId: string, other: string): Promise<Comparison> {
    return firstValueFrom(
      this.http.get<Comparison>(`/api/sheets/${encodeURIComponent(sheetId)}/compare`, { params: { other } }),
    );
  }
}
