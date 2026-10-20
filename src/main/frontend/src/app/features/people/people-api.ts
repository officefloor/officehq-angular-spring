import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Session } from '../../core/session';
import { Claim } from '../claims/claims-api';

export type Grade = 'A' | 'B' | 'C';

/** A grade a person has from a date (yyyy-mm-dd); null for from the beginning of time. */
export interface GradeFrom {
  grade: Grade;
  from: string | null;
}

/** The grade in force on a date (yyyy-mm-dd): the one with the latest from-date on or before it. */
export function gradeOn(grades: readonly GradeFrom[], date: string): Grade | undefined {
  // No from-date sorts as '', before every date.
  let best: GradeFrom | undefined;
  for (const g of grades) {
    const from = g.from ?? '';
    if (from <= date && (!best || from > (best.from ?? ''))) best = g;
  }
  return best?.grade;
}

/** A hold from a date to a date (yyyy-mm-dd), inclusive; open-ended when it has no to-date. */
export interface Hold {
  from: string;
  to: string | null;
}

/** Whether a date (yyyy-mm-dd) is within one of the holds. */
export function heldOn(holds: readonly Hold[], date: string): boolean {
  return holds.some((h) => h.from <= date && (h.to === null || date <= h.to));
}

export interface Person {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the person was read. */
  grade: Grade;
  /** Every grade recorded for the person, by from-date, the first one given (from the beginning of time) first. */
  grades: GradeFrom[];
  /** The person's own terms in days, ahead of their grade's and the sheet's; null for none. */
  terms: number | null;
  /** Raised each time the person is saved. */
  version: number;
  /** How many statements were taken for the person. */
  statementCount: number;
  /** The last statement's due-total ("n/a" when it was), and its date (the date it was taken as of, else the
   * sheet's today when it was taken); null before
   * the first. */
  statedTotal: string | null;
  statementDate: string | null;
  /** The date the person is retired from; null while not retired. */
  retiredFrom: string | null;
  /** Whether the person has any hold. */
  held: boolean;
  /** The person's holds by from-date: their OPEN and PENDING claims dated within one, unless bound by a statement,
   * count in no live total. */
  holds: Hold[];
  /** The ids of the people named as the person's patrons, in the order they were named. */
  patronIds: number[];
  /** Whether the signed-in user watches the person. */
  watched: boolean;
}

export interface PersonForm {
  name: string;
  grade: Grade;
  /** The date a saved grade is in force from (yyyy-mm-dd); null for the sheet's today. A new person's first
   * grade applies from the beginning of time. */
  gradeFrom?: string | null;
  /** The person's own terms in days; blank for none. */
  terms?: string;
  /** The version of the person the editor loaded; a save is refused once someone else has saved since. */
  version?: number;
}

export interface QuickAddForm {
  name: string;
  amount: string;
}

/** What a quick-add stored: the claim, and its person, who is new when `personCreated`. */
export interface QuickAdded {
  person: Person;
  personCreated: boolean;
  claim: Claim;
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

  /** Take a statement for the person, as of a date (null: now). */
  statement(sheetId: string, id: number, asOf: string | null = null): Observable<Person> {
    return this.http.post<Person>(`${this.url(sheetId)}/${id}/statement`, { asOf }, { headers: this.headers() });
  }

  /** Cancel the person's last statement. */
  unstate(sheetId: string, id: number): Observable<Person> {
    return this.http.delete<Person>(`${this.url(sheetId)}/${id}/statement`, { headers: this.headers() });
  }

  /** Retire the person from a date, the sheet's today when none is given. */
  retire(sheetId: string, id: number, from: string | null): Observable<Person> {
    return this.http.post<Person>(`${this.url(sheetId)}/${id}/retire`, { from }, { headers: this.headers() });
  }

  /** Put the person on hold from a date, the sheet's today when none is given, to a date or open-ended. */
  hold(sheetId: string, id: number, from: string | null, to: string | null): Observable<Person> {
    return this.http.post<Person>(`${this.url(sheetId)}/${id}/hold`, { from, to }, { headers: this.headers() });
  }

  /** Start (true) or stop (false) watching the person as the signed-in user. */
  watch(sheetId: string, id: number, watched: boolean): Observable<Person> {
    return this.http.put<Person>(`${this.url(sheetId)}/${id}/watch`, { watched }, { headers: this.headers() });
  }

  /** Name another person on the sheet as the person's patron. */
  addPatron(sheetId: string, id: number, patronId: number): Observable<Person> {
    return this.http.post<Person>(`${this.url(sheetId)}/${id}/patrons`, { patronId }, { headers: this.headers() });
  }

  /** Add a draft claim for the named person, adding them with grade A first when not on the sheet. */
  quickAdd(sheetId: string, form: QuickAddForm): Observable<QuickAdded> {
    return this.http.post<QuickAdded>(`/api/sheets/${encodeURIComponent(sheetId)}/quick-add`, form, {
      headers: this.headers(),
    });
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}
