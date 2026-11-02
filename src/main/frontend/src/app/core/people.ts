import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

export type Grade = 'A' | 'B' | 'C';

export interface Person {
  id: number;
  name: string;
  grade: Grade;
  /** Raised each time the person is saved. */
  version: number;
}

export interface PersonForm {
  name: string;
  grade: Grade;
}

// The people kept on the active sheet.
@Injectable({ providedIn: 'root' })
export class People {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Promise<Person[]> {
    return firstValueFrom(this.http.get<Person[]>(this.url(sheetId)));
  }

  add(form: PersonForm): Promise<Person> {
    const { sheetId, user } = this.active();
    return firstValueFrom(this.http.post<Person>(this.url(sheetId), form, { headers: { 'X-User': user } }));
  }

  /** Save over the version of the person the editor loaded; refused (412) when another user has
   *  saved them since. */
  edit(id: number, version: number, form: PersonForm): Promise<Person> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.put<Person>(`${this.url(sheetId)}/${id}`, { ...form, version }, { headers: { 'X-User': user } }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }

  private active(): { sheetId: string; user: string } {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return { sheetId, user };
  }
}
