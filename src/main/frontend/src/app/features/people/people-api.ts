import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim } from '../claims/claims-api';

export const GRADES = ['A', 'B', 'C'] as const;

/** A grade a person was given, and the first date (yyyy-mm-dd) it is in force; null for their first
 *  grade, in force from the beginning of time. */
export interface DatedGrade {
  grade: string;
  from: string | null;
}

/** Of a person's grades, the one in force on a date (yyyy-mm-dd): the one with the latest from-date
 *  on or before it, else their first. */
export function gradeOn(grades: readonly DatedGrade[], date: string | null): string {
  let best: DatedGrade | null = null;
  for (const g of grades) {
    if (g.from !== null && (date === null || g.from > date)) continue;
    if (best === null || (best.from ?? '') < (g.from ?? '')) best = g;
  }
  return best?.grade ?? grades[0]?.grade ?? '';
}

/** A hold from a date (yyyy-mm-dd) to an optional one (null: open-ended), both inclusive. */
export interface Hold {
  from: string;
  to: string | null;
}

/** Whether any of the holds covers a date (yyyy-mm-dd). */
export function heldOn(holds: readonly Hold[], date: string): boolean {
  return holds.some((h) => h.from <= date && (h.to === null || date <= h.to));
}

export interface Person {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the person was read. */
  grade: string;
  /** Every grade the person has been given, by from-date, the first one (undated) leading. */
  grades: DatedGrade[];
  /** Raised on every save. */
  version: number;
  /** How many statements have been taken of the person. */
  statementCount: number;
  /** The last statement's due-total, with two decimals; null when it was n/a or there is none. */
  statedTotal: string | null;
  /** The sheet's today when the last statement was taken; null with none. */
  statementDate: string | null;
  /** The date the person is retired from; null while not retired. Set, they count as retired. */
  retiredFrom: string | null;
  /** Whether the person has any hold. */
  held: boolean;
  /** The person's holds, in the order they were added: their OPEN claims dated within one count in
   *  no live total, unless bound by a statement. */
  holds: Hold[];
  /** The ids of the person's patrons (other people on the sheet), in the order they were named. */
  patronIds: number[];
}

/** A statement just taken: the person as they now show, and all their claims, newest first. */
export interface Statement {
  person: Person;
  claims: Claim[];
}

export interface PersonForm {
  name: string;
  grade: string;
  /** On an edit, the version of the person the editor loaded. */
  version?: number;
  /** On an edit, the date the grade is in force from (yyyy-mm-dd); null takes the sheet's today. */
  gradeFrom?: string | null;
}

// The people kept on the active sheet, read and written through the server.
@Injectable({ providedIn: 'root' })
export class PeopleApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Promise<Person[]> {
    return firstValueFrom(this.http.get<Person[]>(this.url(sheetId), { headers: this.headers() }));
  }

  add(sheetId: string, form: PersonForm): Promise<Person> {
    return firstValueFrom(this.http.post<Person>(this.url(sheetId), form, { headers: this.headers() }));
  }

  get(sheetId: string, id: number): Promise<Person> {
    return firstValueFrom(this.http.get<Person>(`${this.url(sheetId)}/${id}`, { headers: this.headers() }));
  }

  update(sheetId: string, id: number, form: PersonForm): Promise<Person> {
    return firstValueFrom(this.http.put<Person>(`${this.url(sheetId)}/${id}`, form, { headers: this.headers() }));
  }

  /** Take a statement of a person, binding their counting claims to the dues they show now. */
  statement(sheetId: string, id: number): Promise<Statement> {
    return firstValueFrom(
      this.http.post<Statement>(`/api/sheets/${encodeURIComponent(sheetId)}/people/${id}/statement`, null,
        { headers: this.headers() }),
    );
  }

  /** Retire a person from a date; a blank one takes the sheet's today. */
  retire(sheetId: string, id: number, from: string): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/retire`, { from: from || null }, { headers: this.headers() }),
    );
  }

  /** Add a hold to a person from a date (blank: the sheet's today) to an optional one (blank: open-ended). */
  hold(sheetId: string, id: number, from: string, to: string): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/hold`, { from: from || null, to: to || null },
        { headers: this.headers() }),
    );
  }

  /** Name another person on the sheet as the person's patron. */
  addPatron(sheetId: string, id: number, patronId: number): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/patrons`, { patronId }, { headers: this.headers() }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export function personError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a name, choose a grade and enter a from-date as yyyy-mm-dd or none.';
  if (isStale(e)) return 'Someone else saved this person since you opened it; the form now shows their values.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That name is already in use on this sheet.';
  return 'Something went wrong; try again.';
}

export function statementError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  return 'Something went wrong; try again.';
}

export function retireError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a date as yyyy-mm-dd.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  return 'Something went wrong; try again.';
}

export function holdError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter the dates as yyyy-mm-dd.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'The to-date must not be before the from-date.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  return 'Something went wrong; try again.';
}

export function patronError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Choose a patron.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That person is already a patron.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'A person cannot be their own patron.';
  return 'Something went wrong; try again.';
}

/** Whether a save was refused because someone else saved the person since the editor loaded it. */
export function isStale(e: unknown): boolean {
  return e instanceof HttpErrorResponse && e.status === 412;
}

/** One CSV line ending in a line feed; a value holding a comma, a quote or a line break is quoted, its quotes doubled. */
export function csvLine(values: string[]): string {
  return values.map((v) => (/[",\r\n]/.test(v) ? `"${v.replaceAll('"', '""')}"` : v)).join(',') + '\n';
}
