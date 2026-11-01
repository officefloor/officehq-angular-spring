import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** A snapshot line written for a person when the sheet's today changed: the day being left (yyyy-mm-dd) and their
 *  due-total on it, with exactly two decimals, or "n/a" when the grade had no rate then. */
export interface SnapshotView {
  personId: number;
  day: string;
  dueTotal: string;
}

// The snapshot lines written for people on a sheet, oldest first.
@Injectable({ providedIn: 'root' })
export class Snapshots {
  private readonly http = inject(HttpClient);

  list(sheetId: string): Observable<SnapshotView[]> {
    return this.http.get<SnapshotView[]>(`/api/sheets/${encodeURIComponent(sheetId)}/snapshots`);
  }

  /** Two snapshot dates (yyyy-mm-dd) compared, a line per person in list order: name, due-total in the snapshot of
   *  the from-date (blank: the sheet's today), and in that of the to-date (blank: their due-total now), trimmed. */
  compare(sheetId: string, from: string, to: string): Observable<string[]> {
    const params: Record<string, string> = {};
    if (from !== '') params['from'] = from;
    if (to !== '') params['to'] = to;
    return this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheetId)}/snapshots/compare`, { params });
  }
}
