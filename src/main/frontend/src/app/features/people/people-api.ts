import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export const GRADES = ['A', 'B', 'C'] as const;

export interface Person {
  id: number;
  name: string;
  grade: string;
  /** Raised on every save. */
  version: number;
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

/** Whether a save was refused because someone else saved the person since the editor loaded it. */
export function isStale(e: unknown): boolean {
  return e instanceof HttpErrorResponse && e.status === 412;
}
