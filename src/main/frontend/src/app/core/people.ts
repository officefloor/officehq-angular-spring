import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type Grade = 'A' | 'B' | 'C';
export const GRADES: readonly Grade[] = ['A', 'B', 'C'];

export interface PersonView {
  id: number;
  name: string;
  grade: Grade;
  /** Raised on every save; an edit names the version it was made from. */
  version: number;
}

export interface PersonForm {
  name: string;
  grade: Grade;
}

// The people kept on a sheet, listed in the order they were added.
@Injectable({ providedIn: 'root' })
export class People {
  private readonly http = inject(HttpClient);

  list(sheetId: string): Observable<PersonView[]> {
    return this.http.get<PersonView[]>(this.base(sheetId));
  }

  add(sheetId: string, person: PersonForm): Observable<PersonView> {
    return this.http.post<PersonView>(this.base(sheetId), person);
  }

  /** Save an edit made from `version` of the person; refused (412) when someone has saved them since. */
  update(sheetId: string, id: number, version: number, person: PersonForm): Observable<PersonView> {
    return this.http.put<PersonView>(`${this.base(sheetId)}/${id}`, { ...person, version });
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }
}
