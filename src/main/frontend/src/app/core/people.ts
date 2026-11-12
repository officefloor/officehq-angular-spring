import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

export const GRADES = ['A', 'B', 'C'] as const;

export interface Person {
  id: number;
  name: string;
  grade: string;
}

export interface PersonFields {
  name: string;
  grade: string;
}

// The people on the active sheet, in the order they were added, re-read from the server each
// time the sheet is loaded.
@Injectable({ providedIn: 'root' })
export class People {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly people = signal<Person[]>([]);
  private loads = 0;

  /** Re-read the active sheet's people from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.people.set([]);
    if (!user || !sid) return null;
    try {
      const people = await firstValueFrom(
        this.http.get<Person[]>(this.url(sid), { headers: { 'X-User': user } }),
      );
      if (load === this.loads) this.people.set(people);
      return null;
    } catch (e) {
      return failed('load', e).replace(' person', ' people');
    }
  }

  /** Add a person; an error message when refused. */
  async add(f: PersonFields): Promise<string | null> {
    const refused = this.check(f);
    if (refused) return refused;
    const sid = this.session.sheetId()!;
    try {
      const person = await firstValueFrom(
        this.http.post<Person>(this.url(sid), this.body(f), { headers: { 'X-User': this.session.user()! } }),
      );
      if (this.session.sheetId() === sid) this.people.update((ps) => [...ps, person]);
      return null;
    } catch (e) {
      return failed('add', e);
    }
  }

  /** Change a person's name and grade; an error message when refused. */
  async update(id: number, f: PersonFields): Promise<string | null> {
    const refused = this.check(f);
    if (refused) return refused;
    const sid = this.session.sheetId()!;
    try {
      const person = await firstValueFrom(
        this.http.put<Person>(`${this.url(sid)}/${id}`, this.body(f), {
          headers: { 'X-User': this.session.user()! },
        }),
      );
      if (this.session.sheetId() === sid) this.people.update((ps) => ps.map((p) => (p.id === id ? person : p)));
      return null;
    } catch (e) {
      return failed('save', e);
    }
  }

  private check(f: PersonFields): string | null {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    if (!f.name.trim()) return 'Enter a name.';
    if (!(GRADES as readonly string[]).includes(f.grade)) return 'Choose a grade.';
    return null;
  }

  private body(f: PersonFields): PersonFields {
    return { name: f.name.trim(), grade: f.grade };
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/people`;
  }
}

function failed(what: string, e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That name is already in use.';
  return e instanceof HttpErrorResponse ? `Could not ${what} person (${e.status}).` : `Could not ${what} person.`;
}
