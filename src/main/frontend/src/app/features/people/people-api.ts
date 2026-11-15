import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, otherError } from '../../core/session';
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

/** A past retirement: from a date (yyyy-mm-dd) to the day the person was unretired. */
export interface Retirement {
  from: string;
  to: string;
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
  /** The date the last statement was taken as of, else the sheet's today when it was taken; null with none. */
  statementDate: string | null;
  /** The date the person is retired from; null while not retired. Set, they count as retired. */
  retiredFrom: string | null;
  /** The person's past retirements, in the order they were unretired: from a date to the day
   *  they were unretired. No claim dated after the from and before the to can be added. */
  retirements: Retirement[];
  /** Whether the person has any hold. */
  held: boolean;
  /** The person's holds, in the order they were added: their OPEN claims dated within one count in
   *  no live total, unless bound by a statement. */
  holds: Hold[];
  /** The ids of the person's patrons (other people on the sheet), in the order they were named. */
  patronIds: number[];
  /** Whether the signed-in user watches the person. */
  watched: boolean;
  /** The person's own terms in days, ahead of their grade's and the sheet's; null when none. */
  terms: number | null;
  /** The person's contact, trimmed and lower-cased; null when none. */
  contact: string | null;
  /** The person's aliases, sorted as names are compared (ignoring case). */
  aliases: string[];
  /** Whether their name or an alias is also a name or alias on another sheet of the sheet's tree. */
  clashing: boolean;
  /** The id of the sheet the person was transferred from; null for anyone not transferred here. */
  origin: string | null;
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
  /** The person's own terms in whole days; blank for none. */
  terms: string;
  /** The person's contact; blank for none. */
  contact: string;
}

// The people kept on the active sheet, read and written through the server.
/** Figures added up over a sheet's descendants: how many people (retired ones too), and each
 *  sheet's due-total and overdue-total as that sheet shows it. */
export interface DescendantFigures {
  people: number;
  dueTotal: string;
  overdueTotal: string;
}

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

  /** Add or update a person for each pasted line of name,grade,terms,contact, in order; all or none. */
  importPeople(sheetId: string, text: string): Promise<ImportedPeople> {
    return firstValueFrom(this.http.post<ImportedPeople>(`/api/sheets/${encodeURIComponent(sheetId)}/import-people`, { text },
      { headers: this.headers() }));
  }

  get(sheetId: string, id: number): Promise<Person> {
    return firstValueFrom(this.http.get<Person>(`${this.url(sheetId)}/${id}`, { headers: this.headers() }));
  }

  update(sheetId: string, id: number, form: PersonForm): Promise<Person> {
    return firstValueFrom(this.http.put<Person>(`${this.url(sheetId)}/${id}`, form, { headers: this.headers() }));
  }

  /** Take a statement of a person, binding their counting claims to the dues they show now, or as
   *  of a date (blank: now) to the dues they showed at the end of it. */
  statement(sheetId: string, id: number, asOf: string): Promise<Statement> {
    return firstValueFrom(
      this.http.post<Statement>(`/api/sheets/${encodeURIComponent(sheetId)}/people/${id}/statement`, { asOf: asOf || null },
        { headers: this.headers() }),
    );
  }

  /** Cancel a person's last statement (a steward only): the claims it bound are live again. */
  cancelStatement(sheetId: string, id: number): Promise<Statement> {
    return firstValueFrom(
      this.http.post<Statement>(`/api/sheets/${encodeURIComponent(sheetId)}/people/${id}/statement/cancel`, null,
        { headers: this.headers() }),
    );
  }

  /** Give the people of the ids, in list order, a grade from a date (blank: the sheet's today); how many were changed. */
  bulkGrade(sheetId: string, ids: number[], grade: string, from: string): Promise<{ changed: number }> {
    return firstValueFrom(
      this.http.post<{ changed: number }>(`${this.url(sheetId)}/bulk-grade`, { ids, grade, from: from || null },
        { headers: this.headers() }),
    );
  }

  /** Retire the people of the ids, in list order, from a date (blank: the sheet's today); how many were retired. */
  bulkRetire(sheetId: string, ids: number[], from: string): Promise<{ retired: number }> {
    return firstValueFrom(
      this.http.post<{ retired: number }>(`${this.url(sheetId)}/bulk-retire`, { ids, from: from || null },
        { headers: this.headers() }),
    );
  }

  /** Retire a person from a date; a blank one takes the sheet's today. */
  retire(sheetId: string, id: number, from: string): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/retire`, { from: from || null }, { headers: this.headers() }),
    );
  }

  /** Unretire a retired person on the sheet's today. */
  unretire(sheetId: string, id: number): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/unretire`, null, { headers: this.headers() }),
    );
  }

  /** Move a person one place up or down the list; the sheet's people in their new order. */
  move(sheetId: string, id: number, direction: 'up' | 'down'): Promise<Person[]> {
    return firstValueFrom(
      this.http.post<Person[]>(`${this.url(sheetId)}/${id}/move`, { direction }, { headers: this.headers() }),
    );
  }

  /** Add a hold to a person from a date (blank: the sheet's today) to an optional one (blank: open-ended). */
  hold(sheetId: string, id: number, from: string, to: string): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/hold`, { from: from || null, to: to || null },
        { headers: this.headers() }),
    );
  }

  /** Start (on) or stop watching a person as the signed-in user. */
  watch(sheetId: string, id: number, on: boolean): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/watch`, { on }, { headers: this.headers() }),
    );
  }

  /** Give a person an alias. */
  addAlias(sheetId: string, id: number, alias: string): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/aliases`, { alias }, { headers: this.headers() }),
    );
  }

  /** Name another person on the sheet as the person's patron. */
  addPatron(sheetId: string, id: number, patronId: number): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/patrons`, { patronId }, { headers: this.headers() }),
    );
  }

  /** Merge the person into another; resolves to the person merged into. */
  merge(sheetId: string, id: number, intoId: number): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/merge`, { intoId }, { headers: this.headers() }),
    );
  }

  /** The sheet's own figures as it is now, even while the screen shows it as of a date. */
  ownFigures(sheetId: string): Promise<DescendantFigures> {
    return firstValueFrom(this.http.get<DescendantFigures>(
      `/api/sheets/${encodeURIComponent(sheetId)}/figures`, { headers: this.headers() }));
  }

  /** Transfer the person to another sheet; resolves to the person as they arrived there. */
  transfer(sheetId: string, id: number, toSheetId: string): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/transfer`, { toSheetId }, { headers: this.headers() }),
    );
  }

  /** The figures of the sheet's descendants (its children, theirs, and so on), each as it is now, added up. */
  descendantFigures(sheetId: string): Promise<DescendantFigures> {
    return firstValueFrom(this.http.get<DescendantFigures>(
      `/api/sheets/${encodeURIComponent(sheetId)}/descendants/figures`, { headers: this.headers() }));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export function personError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a name, choose a grade, enter a from-date as yyyy-mm-dd or none and terms as whole days or none.';
  if (isStale(e)) return 'Someone else saved this person since you opened it; the form now shows their values.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That name, alias or contact is already in use on this sheet.';
  return otherError(e);
}

/** The people an import added and those it updated (a line naming a person on the sheet). */
export interface ImportedPeople {
  added: Person[];
  updated: Person[];
}

export function importError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) {
    return 'Paste lines of name,grade,terms,contact: a name, a grade A, B or C, terms as whole days or blank, and a contact or blank.';
  }
  if (e instanceof HttpErrorResponse && e.status === 409) {
    return 'Every person and contact may be given only once in the paste, and a new contact must not be in use on this sheet.';
  }
  return otherError(e);
}

export function bulkGradeError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Select people, choose a grade and enter a from-date as yyyy-mm-dd or none.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'A selected person is no longer on this sheet; reload it.';
  if (isStale(e)) return 'Someone else saved a selected person at the same moment; try again.';
  return otherError(e);
}

export function bulkRetireError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Select people and enter a from-date as yyyy-mm-dd or none.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'A selected person is no longer on this sheet; reload it.';
  if (isStale(e)) return 'Someone else saved a selected person at the same moment; try again.';
  return otherError(e);
}

export function statementError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a date as yyyy-mm-dd or none.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'A statement is taken as of a date on or before the sheet\'s today, while the person was on the sheet.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  return otherError(e);
}

export function cancelStatementError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward may cancel a statement.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'This person has no statement to cancel.';
  return statementError(e);
}

export function retireError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a date as yyyy-mm-dd.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  return otherError(e);
}

export function unretireError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'Only a retired person can be unretired.';
  return otherError(e);
}

export function moveError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 422) return 'The top person cannot move up, nor the bottom one down.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  return otherError(e);
}

export function holdError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter the dates as yyyy-mm-dd.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'The to-date must not be before the from-date.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  return otherError(e);
}

export function watchError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  return otherError(e);
}

export function aliasError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter an alias of at most 200 characters.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That name or alias is already in use on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'Enter an alias.';
  return otherError(e);
}

export function mergeError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Choose a person to merge into.';
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward may merge people.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'A person is merged only into someone else, neither of them retired, with pool shares of at most 100.00.';
  return otherError(e);
}

export function transferError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter the id of the sheet to transfer to.';
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward may transfer people, and neither sheet may be archived.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Their name, an alias or their contact is already on that sheet.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'A person is transferred only to another sheet, and not once retired.';
  return otherError(e);
}

export function patronError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Choose a patron.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That person is already a patron.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'A person cannot be their own patron.';
  return otherError(e);
}

/** Whether a save was refused because someone else saved the person since the editor loaded it. */
export function isStale(e: unknown): boolean {
  return e instanceof HttpErrorResponse && e.status === 412;
}

/** One CSV line ending in a line feed; a value holding a comma, a quote or a line break is quoted, its quotes doubled. */
export function csvLine(values: string[]): string {
  return values.map((v) => (/[",\r\n]/.test(v) ? `"${v.replaceAll('"', '""')}"` : v)).join(',') + '\n';
}
