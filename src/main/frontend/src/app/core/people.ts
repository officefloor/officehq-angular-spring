import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

export type Grade = 'A' | 'B' | 'C';
export const GRADES: readonly Grade[] = ['A', 'B', 'C'];

export interface Person {
  id: number;
  name: string;
  grade: Grade;
  /** Raised each time the person is saved; an edit sends back the version it was made from. */
  version: number;
}

export interface PersonFields {
  name: string;
  grade: Grade;
}

/**
 * What an import did: how many it added and how many existing people it updated, or the lines
 * (numbered from 1) that stopped it.
 */
export interface ImportResult {
  added: number;
  updated: number;
  errors: { line: number; message: string }[];
}

// The people kept on the active sheet, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class PeopleApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(): Promise<Person[]> {
    return firstValueFrom(this.http.get<Person[]>(this.url(), { headers: this.headers() }));
  }

  add(fields: PersonFields): Promise<Person> {
    return firstValueFrom(this.http.post<Person>(this.url(), fields, { headers: this.headers() }));
  }

  /**
   * Add or update people from pasted lines of name,grade; all of them, or none when any line is bad.
   */
  importLines(text: string): Promise<ImportResult> {
    return firstValueFrom(
      this.http.post<ImportResult>(`${this.url()}/import`, { text }, { headers: this.headers() }),
    );
  }

  /** Refused with 412 when someone else has saved the person since this version was read. */
  update(id: number, version: number, fields: PersonFields): Promise<Person> {
    return firstValueFrom(
      this.http.put<Person>(`${this.url()}/${id}`, { ...fields, version }, { headers: this.headers() }),
    );
  }

  private url(): string {
    const id = this.session.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return `/api/sheets/${encodeURIComponent(id)}/people`;
  }

  private headers(): Record<string, string> {
    const user = this.session.user();
    if (!user) throw new Error('not signed in');
    return { 'X-User': user };
  }
}
