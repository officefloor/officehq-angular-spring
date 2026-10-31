import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Whether the signed-in user watches a person. */
export interface WatchView {
  personId: number;
  watched: boolean;
}

// The people the signed-in user watches on a sheet.
@Injectable({ providedIn: 'root' })
export class Watches {
  private readonly http = inject(HttpClient);

  /** The ids of the people the signed-in user watches. */
  list(sheetId: string): Observable<number[]> {
    return this.http.get<number[]>(`/api/sheets/${encodeURIComponent(sheetId)}/watches`);
  }

  /** Start or stop watching the person; refused when already so. */
  set(sheetId: string, personId: number, watched: boolean): Observable<WatchView> {
    return this.http.put<WatchView>(
      `/api/sheets/${encodeURIComponent(sheetId)}/people/${personId}/watch`,
      { watched },
    );
  }
}
