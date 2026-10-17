import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

export type Grade = 'A' | 'B' | 'C';
export const GRADES: readonly Grade[] = ['A', 'B', 'C'];

export interface Person {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the person was read. */
  grade: Grade;
  /** The grades recorded, oldest first; the first has no from-date and applies from the beginning of time. */
  grades: GradeFrom[];
  /** Raised each time the person is saved; an edit sends back the version it was made from. */
  version: number;
  /** yyyy-mm-dd the person is retired from; null while they are not retired. */
  retiredFrom: string | null;
  /** Whether the person is on hold: while held, their open claims count in no live total. */
  held: boolean;
  /** The ids of the other people on the sheet this person names as their patrons. */
  patronIds: number[];
}

/** A grade recorded for a person, in force from its from-date (yyyy-mm-dd), or from the beginning of time when null. */
export interface GradeFrom {
  from: string | null;
  grade: Grade;
}

/** A person's grade in force on a date (yyyy-mm-dd): the one with the latest from-date on or before it. */
export function gradeOn(grades: readonly GradeFrom[], date: string): Grade {
  let found: GradeFrom | undefined;
  for (const g of grades) {
    if (g.from !== null && g.from > date) continue;
    if (found === undefined || (found.from ?? '') <= (g.from ?? '')) found = g;
  }
  return (found ?? grades[0]).grade;
}

/** Whether a claim dated on this date (yyyy-mm-dd) is refused: it is after the person's retirement. */
export function retiredOn(person: { retiredFrom: string | null }, date: string): boolean {
  return person.retiredFrom !== null && date > person.retiredFrom;
}

export interface PersonFields {
  name: string;
  grade: Grade;
}

/** An edit also gives the date (yyyy-mm-dd) its grade applies from; the sheet's today when empty. */
export interface PersonEditFields extends PersonFields {
  gradeFrom: string;
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

  /** Whether retired people are listed; kept across sheet (re-)loads. */
  readonly showRetired = signal(false);

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

  /**
   * Save the name, and record the grade from its from-date, keeping the grades already recorded.
   * Refused with 412 when someone else has saved the person since this version was read. */
  update(id: number, version: number, fields: PersonEditFields): Promise<Person> {
    const body = { ...fields, gradeFrom: fields.gradeFrom || null, version };
    return firstValueFrom(
      this.http.put<Person>(`${this.url()}/${id}`, body, { headers: this.headers() }),
    );
  }

  /** Retire the person from a date; refused with 409 when they are already retired. */
  retire(id: number, from: string): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url()}/${id}/retire`, { from: from || null }, { headers: this.headers() }),
    );
  }

  /** Put the person on hold, or release them; refused with 409 when they already are (or are not). */
  setHeld(id: number, held: boolean): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url()}/${id}/${held ? 'hold' : 'release'}`, null, { headers: this.headers() }),
    );
  }

  /** Name another person as a patron; refused with 422 for themselves and 409 when already named. */
  addPatron(id: number, patronId: number): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url()}/${id}/patrons`, { patronId }, { headers: this.headers() }),
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
