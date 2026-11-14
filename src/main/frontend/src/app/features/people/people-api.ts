import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim } from '../claims/claims-api';

export const GRADES = ['A', 'B', 'C'] as const;

export interface Person {
  id: number;
  name: string;
  grade: string;
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

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export function personError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a name and choose a grade.';
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

/** Whether a save was refused because someone else saved the person since the editor loaded it. */
export function isStale(e: unknown): boolean {
  return e instanceof HttpErrorResponse && e.status === 412;
}
