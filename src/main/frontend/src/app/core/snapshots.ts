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
}
