import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { Claim } from './claims';
import { Session } from './session';

export type Grade = 'A' | 'B' | 'C';

export interface Person {
  id: number;
  name: string;
  grade: Grade;
  /** Raised each time the person is saved. */
  version: number;
  /** How many statements were taken of them. */
  statementCount: number;
  /** The last statement's due-total as text with two decimals; null when it was n/a or none was taken. */
  statedTotal: string | null;
  /** The day (yyyy-mm-dd) the last statement was taken; null when none was. */
  statementDate: string | null;
  /** Whether they are retired: they are as soon as a retirement day is set, whatever the day. */
  retired: boolean;
  /** The day (yyyy-mm-dd) they are retired from; null when they are not retired. */
  retiredFrom: string | null;
}

/** A statement just taken: the person as they now show, and their claims (newest first) as it
 *  bound them. */
export interface Taken {
  person: Person;
  claims: Claim[];
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

  /** Retire the person from a day; null takes the sheet's today. */
  retire(id: number, from: string | null): Promise<Person> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/retire`, { from }, { headers: { 'X-User': user } }),
    );
  }

  /** Take a statement of the person: their due-total now is recorded and each claim of theirs that
   *  counts is bound to the figure it has now. */
  statement(id: number): Promise<Taken> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Taken>(`${this.url(sheetId)}/${id}/statements`, null, { headers: { 'X-User': user } }),
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
