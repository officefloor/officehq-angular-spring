import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Claim } from './claims';
import { Session } from './session';

export const GRADES = ['A', 'B', 'C'] as const;

export interface Person {
  id: number;
  name: string;
  grade: string;
  /** Moved on by each save; an edit made from an older version is refused. */
  version: number;
  /** How many statements were taken of them. */
  statementCount: number;
  /** The last statement's due-total, two decimals or n/a; null before their first. */
  statedTotal: string | null;
  /** The sheet's today when the last statement was taken; null before their first. */
  statementDate: string | null;
}

/** A person after a statement, with their claims as it bound them, newest first. */
export interface Statement {
  person: Person;
  claims: Claim[];
}

/** A refused save: why, and the person as stored now when someone else saved them first. */
export interface Refusal {
  error: string;
  current?: Person;
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

  /** Change a person's name and grade, from the version the editor loaded; the refusal when
   *  refused, carrying the person as stored now when someone else saved them in the meantime. */
  async update(id: number, version: number, f: PersonFields): Promise<Refusal | null> {
    const refused = this.check(f);
    if (refused) return { error: refused };
    const sid = this.session.sheetId()!;
    const put = (person: Person) => {
      if (this.session.sheetId() === sid) this.people.update((ps) => ps.map((p) => (p.id === id ? person : p)));
    };
    try {
      put(
        await firstValueFrom(
          this.http.put<Person>(`${this.url(sid)}/${id}`, { ...this.body(f), version }, {
            headers: { 'X-User': this.session.user()! },
          }),
        ),
      );
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 412) {
        const current = e.error as Person;
        put(current);
        return { error: 'Someone else has changed this person. Their values are shown; review and save again.', current };
      }
      return { error: failed('save', e) };
    }
  }

  /** Take a person's statement: the person and their claims as it left them, or an error message
   *  when refused. */
  async statement(id: number): Promise<Statement | string> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const stated = await firstValueFrom(
        this.http.post<Statement>(`${this.url(sid)}/${id}/statement`, null, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) {
        this.people.update((ps) => ps.map((p) => (p.id === id ? stated.person : p)));
      }
      return stated;
    } catch (e) {
      return failed('take statement of', e);
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
