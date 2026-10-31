import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** A person's patron: another person on the sheet that the person names. */
export interface PatronView {
  id: number;
  personId: number;
  patronId: number;
}

// The patrons people name on a sheet.
@Injectable({ providedIn: 'root' })
export class Patrons {
  private readonly http = inject(HttpClient);

  list(sheetId: string): Observable<PatronView[]> {
    return this.http.get<PatronView[]>(`/api/sheets/${encodeURIComponent(sheetId)}/patrons`);
  }

  /** Name `patronId` as the person's patron; refused when it is the person or already their patron. */
  add(sheetId: string, personId: number, patronId: number): Observable<PatronView> {
    return this.http.post<PatronView>(
      `/api/sheets/${encodeURIComponent(sheetId)}/people/${personId}/patrons`,
      { patronId },
    );
  }
}
