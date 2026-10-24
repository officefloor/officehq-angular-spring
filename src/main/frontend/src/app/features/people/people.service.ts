import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export type Grade = 'A' | 'B' | 'C';

export const GRADES: readonly Grade[] = ['A', 'B', 'C'];

export interface Person {
  id: number;
  name: string;
  grade: Grade;
  /** Raised each time the person is saved. */
  version: number;
}

export interface PersonFields {
  name: string;
  grade: Grade;
}

// The people kept on a sheet, read from and written to the server.
@Injectable({ providedIn: 'root' })
export class PeopleService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheet: string): Promise<Person[]> {
    return firstValueFrom(
      this.http.get<Person[]>(this.url(sheet), { headers: this.session.headers() }),
    );
  }

  add(sheet: string, fields: PersonFields): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(this.url(sheet), fields, { headers: this.session.headers() }),
    );
  }

  /** Save the person as edited from `version`; refused with 409 and the person as now stored
   *  (`current`) when someone else has saved them since. */
  update(sheet: string, id: number, version: number, fields: PersonFields): Promise<Person> {
    return firstValueFrom(
      this.http.put<Person>(`${this.url(sheet)}/${id}`, { ...fields, version }, {
        headers: this.session.headers(),
      }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/people`;
  }
}
