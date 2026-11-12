import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Claim } from './claims';
import { Session } from './session';

export const GRADES = ['A', 'B', 'C'] as const;

/** A grade a person was given, from a date (YYYY-MM-DD); null for the first, from the beginning of time. */
export interface GradeFrom {
  grade: string;
  from: string | null;
}

/** The grade in force on a date (YYYY-MM-DD): the one whose from-date is the latest on or before it. */
export function gradeOn(grades: GradeFrom[], date: string): string {
  let found: string | undefined;
  for (const g of grades) {
    if (found === undefined || g.from === null || g.from <= date) found = g.grade;
  }
  return found ?? '';
}

/** A hold on a person: from a date (null for the beginning of time) to a date (null while it has no
 *  end), both inclusive. */
export interface Hold {
  from: string | null;
  to: string | null;
}

/** Whether one of the holds covers a date (YYYY-MM-DD). */
export function heldOn(holds: Hold[], date: string): boolean {
  return holds.some((h) => (h.from === null || h.from <= date) && (h.to === null || date <= h.to));
}

/** Whether a person's retirement date is reached on a day (the sheet's today): it is on or before it. */
export function retiredBy(p: { retiredFrom: string | null }, today: string | null): boolean {
  return p.retiredFrom !== null && today !== null && p.retiredFrom <= today;
}

export interface Person {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the server answered. */
  grade: string;
  /** Every grade they were given, the first (no from-date) first, then by from-date. */
  grades: GradeFrom[];
  /** Moved on by each save; an edit made from an older version is refused. */
  version: number;
  /** How many statements were taken of them. */
  statementCount: number;
  /** The last statement's due-total, two decimals or n/a; null before their first. */
  statedTotal: string | null;
  /** The last statement's date: the sheet's today when it was taken, or the date it was taken as of; null
   *  before their first. */
  statementDate: string | null;
  /** Whether a retirement date is set: then they count as retired at once, whatever the date. */
  retired: boolean;
  /** The date they are retired from (YYYY-MM-DD); null while not retired. */
  retiredFrom: string | null;
  /** Whether they have any hold at all. */
  held: boolean;
  /** Their holds, by from-date: an OPEN or PENDING claim dated within one, and bound by no statement,
   *  counts in no live total. */
  holds: Hold[];
  /** The ids of the other people on the sheet they have named as their patrons. */
  patronIds: number[];
  /** Whether the signed-in user watches them. */
  watched: boolean;
  /** Their own terms in days, ahead of their grade's and the sheet's; null when they have none. */
  terms: number | null;
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
  /** The date an edit's grade applies from (YYYY-MM-DD); blank takes the sheet's today. */
  gradeFrom?: string;
  /** Their own terms in days; blank for none. */
  terms?: string;
}

// The people on the active sheet, in the order they were added, re-read from the server each
// time the sheet is loaded.
@Injectable({ providedIn: 'root' })
export class People {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly people = signal<Person[]>([]);
  /** Whether the list shows retired people; belongs to the screen, so loading a sheet leaves it. */
  readonly showRetired = signal(false);
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
          this.http.put<Person>(`${this.url(sid)}/${id}`, { ...this.body(f), gradeFrom: f.gradeFrom?.trim() || null, version }, {
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

  /** Take a person's statement, as of a date on or before the sheet's today (blank takes it now): the
   *  person and their claims as it left them, or an error message when refused. */
  async statement(id: number, asOf = ''): Promise<Statement | string> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    const d = asOf.trim();
    if (d && !/^\d{4}-\d{2}-\d{2}$/.test(d)) return 'Enter a date as YYYY-MM-DD.';
    try {
      const stated = await firstValueFrom(
        this.http.post<Statement>(`${this.url(sid)}/${id}/statement`, { asOf: d || null }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) {
        this.people.update((ps) => ps.map((p) => (p.id === id ? stated.person : p)));
      }
      return stated;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409 && typeof e.error?.message === 'string') {
        return e.error.message;
      }
      return failed('take statement of', e);
    }
  }

  /** Cancel a person's last statement: the person and their claims as it left them, or an error message
   *  when refused. */
  async cancelStatement(id: number): Promise<Statement | string> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const unstated = await firstValueFrom(
        this.http.post<Statement>(`${this.url(sid)}/${id}/statement/cancel`, null, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) {
        this.people.update((ps) => ps.map((p) => (p.id === id ? unstated.person : p)));
      }
      return unstated;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409 && typeof e.error?.message === 'string') {
        return e.error.message;
      }
      return failed('cancel the statement of', e);
    }
  }

  /** Retire a person from a date (blank takes the sheet's today); an error message when refused. */
  async retire(id: number, from: string): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !/^\d{4}-\d{2}-\d{2}$/.test(d)) return 'Enter a date as YYYY-MM-DD.';
    try {
      const person = await firstValueFrom(
        this.http.post<Person>(`${this.url(sid)}/${id}/retire`, { from: d || null }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.people.update((ps) => ps.map((p) => (p.id === id ? person : p)));
      return null;
    } catch (e) {
      return failed('retire', e);
    }
  }

  /** Put a person on hold from a date (blank takes the sheet's today) to a date (blank: no end); an
   *  error message when refused. */
  async hold(id: number, from: string, to: string): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    const f = from.trim();
    const t = to.trim();
    const date = /^\d{4}-\d{2}-\d{2}$/;
    if ((f && !date.test(f)) || (t && !date.test(t))) return 'Enter a date as YYYY-MM-DD.';
    try {
      const person = await firstValueFrom(
        this.http.post<Person>(`${this.url(sid)}/${id}/holds`, { from: f || null, to: t || null }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.session.sheetId() === sid) this.people.update((ps) => ps.map((p) => (p.id === id ? person : p)));
      return null;
    } catch (e) {
      return failed('hold', e);
    }
  }

  /** Name another person on the sheet as a person's patron; an error message when refused. */
  async addPatron(id: number, patronId: number): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    if (patronId === id) return 'A person cannot be their own patron.';
    try {
      const person = await firstValueFrom(
        this.http.post<Person>(`${this.url(sid)}/${id}/patrons`, { patronId }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.people.update((ps) => ps.map((p) => (p.id === id ? person : p)));
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        const err: unknown = e.error;
        if (err && typeof err === 'object' && 'message' in err && typeof err.message === 'string') return err.message;
      }
      return failed('add patron to', e);
    }
  }

  /** Start (on) or stop watching a person as the signed-in user; an error message when refused. */
  async watch(id: number, on: boolean): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const person = await firstValueFrom(
        this.http.put<Person>(`${this.url(sid)}/${id}/watch`, { watched: on }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.people.update((ps) => ps.map((p) => (p.id === id ? person : p)));
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        const err: unknown = e.error;
        if (err && typeof err === 'object' && 'message' in err && typeof err.message === 'string') return err.message;
      }
      return failed(on ? 'watch' : 'stop watching', e);
    }
  }

  private check(f: PersonFields): string | null {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    if (!f.name.trim()) return 'Enter a name.';
    if (!(GRADES as readonly string[]).includes(f.grade)) return 'Choose a grade.';
    const from = f.gradeFrom?.trim();
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) return 'Enter the grade\'s from-date as YYYY-MM-DD.';
    const terms = f.terms?.trim();
    if (terms && !/^\d{1,6}$/.test(terms)) return 'Enter the terms as a whole number of days, or leave them blank.';
    return null;
  }

  private body(f: PersonFields): { name: string; grade: string; terms: number | null } {
    const terms = f.terms?.trim();
    return { name: f.name.trim(), grade: f.grade, terms: terms ? Number(terms) : null };
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/people`;
  }
}

function failed(what: string, e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That name is already in use.';
  return e instanceof HttpErrorResponse ? `Could not ${what} person (${e.status}).` : `Could not ${what} person.`;
}
