import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ClaimView } from './claims';

export const GRADES = ['A', 'B', 'C'] as const;
export type Grade = (typeof GRADES)[number];

/** A grade a person has, from a date (yyyy-mm-dd); null from the beginning of time. */
export interface GradeView {
  grade: Grade;
  from: string | null;
}

/**
 * The grade a person has in force on a date (yyyy-mm-dd): the one recorded from the latest date on or
 * before it, else the first they were given (from the beginning of time).
 */
export function gradeOn(grades: GradeView[], date: string): Grade {
  let on = grades[0];
  for (const g of grades) {
    if (g.from === null || g.from > date) continue;
    if (on.from === null || g.from > on.from) on = g;
  }
  return on.grade;
}

export interface PersonView {
  id: number;
  name: string;
  /** The person's grades by the date each is in force from, the first from the beginning of time. */
  grades: GradeView[];
  /** The person's own terms in days; null when they have none and take their grade's terms. */
  terms: number | null;
  /** The person's contact, trimmed and lower-cased; null when they have none. */
  contact: string | null;
  /** Raised on every save of the person. */
  version: number;
  /** How many statements have been taken for the person. */
  statementCount: number;
  /** The last statement's due-total with two decimals; null when it was n/a, or none was taken. */
  statementTotal: string | null;
  /** The date (yyyy-mm-dd) the last statement was taken as of; null when none was. */
  statementDate: string | null;
  /** The date (yyyy-mm-dd) the person is retired from; null when they are not retired. */
  retiredFrom: string | null;
  /** Whether the person has any hold, whatever its dates. */
  held: boolean;
  /** The person's holds in the order they were added: their unbound OPEN claims dated within one count in no live total. */
  holds: HoldView[];
  /** The ids of the other people on the sheet the person has named as their patrons. */
  patronIds: number[];
  /** The other names the person goes by; absent from a view kept before aliases were. */
  aliases?: string[];
}

/** A hold on a person, from a date to a date (yyyy-mm-dd, both included); to is null with no end. */
export interface HoldView {
  from: string;
  to: string | null;
}

/** Whether a date (yyyy-mm-dd) falls within any of a person's holds. */
export function heldOn(person: PersonView, date: string): boolean {
  return person.holds.some((h) => h.from <= date && (h.to === null || date <= h.to));
}

/** Whether a person's retirement date is reached on the sheet's today (yyyy-mm-dd). */
export function retiredOn(person: PersonView, today: string): boolean {
  return person.retiredFrom !== null && today !== '' && person.retiredFrom <= today;
}

/**
 * Whether a person's OPEN or PENDING claim dated on a date, unbound by a statement, is left out of the
 * live totals on the sheet's today: dated within one of their holds, or once their retirement date is reached.
 */
export function leavesLive(person: PersonView, date: string, today: string): boolean {
  return heldOn(person, date) || retiredOn(person, today);
}

/** A statement taken: the person as stated and their claims, newest first. */
export interface StatementView {
  person: PersonView;
  claims: ClaimView[];
}

export interface PersonForm {
  name: string;
  grade: Grade;
  /** On an edit, the date (yyyy-mm-dd) the grade is in force from; null for the sheet's today. */
  gradeFrom?: string | null;
  /** The person's own terms in days; null for none. */
  terms: number | null;
  /** The person's contact as typed; blank for none. */
  contact: string;
  /** On an edit, whether the terms were typed on the form: PERSON_EDITED then carries them. */
  termsGiven?: boolean;
  /** On an edit, whether the contact was typed on the form: PERSON_EDITED then carries it. */
  contactGiven?: boolean;
  /** On an edit, the version of the person the form loaded. */
  version?: number;
}

// The people kept on a sheet, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class PeopleApi {
  private readonly http = inject(HttpClient);

  /** The sheet's people in the order they were added. */
  list(sheet: string): Promise<PersonView[]> {
    return firstValueFrom(this.http.get<PersonView[]>(this.url(sheet)));
  }

  add(sheet: string, form: PersonForm): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(this.url(sheet), form));
  }

  edit(sheet: string, id: number, form: PersonForm): Promise<PersonView> {
    return firstValueFrom(this.http.put<PersonView>(`${this.url(sheet)}/${id}`, form));
  }

  /**
   * Give the selected people a grade from a date (null: the sheet's today) together, each as the person
   * form would, in the order they were added: the people changed.
   */
  gradeAll(sheet: string, personIds: number[], grade: Grade, from: string | null): Promise<PersonView[]> {
    return firstValueFrom(this.http.post<PersonView[]>(`${this.url(sheet)}/grade`, { personIds, grade, from }));
  }

  /**
   * Take a statement as of a date on or before the sheet's today (null: now): record the person's
   * due-total and bind the claims that counted then to their figures as they stood then.
   */
  statement(sheet: string, id: number, asOf: string | null = null): Promise<StatementView> {
    return firstValueFrom(this.http.post<StatementView>(`${this.url(sheet)}/${id}/statements`, { asOf }));
  }

  /** Cancel the last statement: the claims it bound are live again, the one before it shown. */
  unstate(sheet: string, id: number): Promise<StatementView> {
    return firstValueFrom(this.http.delete<StatementView>(`${this.url(sheet)}/${id}/statements/last`));
  }

  /** Retire a person from a date; with none, from the sheet's today. */
  retire(sheet: string, id: number, from: string | null): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/retire`, { from }));
  }

  /** Put a person on hold from a date (none: the sheet's today) to a date (none: no end). */
  hold(sheet: string, id: number, from: string | null, to: string | null): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/holds`, { from, to }));
  }

  /** Name another person on the sheet as a patron of this one. */
  addPatron(sheet: string, id: number, patronId: number): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/patrons`, { patronId }));
  }

  /** Give a person an alias: it must not equal, as names are compared, any name or alias on the sheet. */
  addAlias(sheet: string, id: number, alias: string): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/aliases`, { alias }));
  }

  /** The ids of the people on the sheet the signed-in user watches. */
  watched(sheet: string): Promise<number[]> {
    return firstValueFrom(this.http.get<number[]>(`${this.url(sheet)}/watched`));
  }

  /** Start (true) or stop (false) watching a person as the signed-in user. */
  watch(sheet: string, id: number, on: boolean): Promise<void> {
    const url = `${this.url(sheet)}/${id}/watch`;
    return firstValueFrom(on ? this.http.put<void>(url, null) : this.http.delete<void>(url));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/people`;
  }
}
