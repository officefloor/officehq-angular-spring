import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Session } from '../../core/session';

export type Grade = 'A' | 'B' | 'C';

export interface Person {
  id: number;
  name: string;
  grade: Grade;
  /** Raised each time the person is saved. */
  version: number;
  /** How many statements were taken for the person. */
  statementCount: number;
  /** The last statement's due-total ("n/a" when it was), and the sheet's today when it was taken; null before
   * the first. */
  statedTotal: string | null;
  statementDate: string | null;
}

export interface PersonForm {
  name: string;
  grade: Grade;
  /** The version of the person the editor loaded; a save is refused once someone else has saved since. */
  version?: number;
}

// The people kept on the active sheet, read and changed through the server.
@Injectable({ providedIn: 'root' })
export class PeopleApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Observable<Person[]> {
    return this.http.get<Person[]>(this.url(sheetId), { headers: this.headers() });
  }

  add(sheetId: string, form: PersonForm): Observable<Person> {
    return this.http.post<Person>(this.url(sheetId), form, { headers: this.headers() });
  }

  update(sheetId: string, id: number, form: PersonForm): Observable<Person> {
    return this.http.put<Person>(`${this.url(sheetId)}/${id}`, form, { headers: this.headers() });
  }

  /** Take a statement for the person. */
  statement(sheetId: string, id: number): Observable<Person> {
    return this.http.post<Person>(`${this.url(sheetId)}/${id}/statement`, null, { headers: this.headers() });
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}
