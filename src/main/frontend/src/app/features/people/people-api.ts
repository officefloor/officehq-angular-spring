import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export const GRADES = ['A', 'B', 'C'] as const;

export interface Person {
  id: number;
  name: string;
  grade: string;
}

export interface PersonForm {
  name: string;
  grade: string;
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
  return e instanceof HttpErrorResponse && e.status === 400
    ? 'Enter a name and choose a grade.'
    : 'Something went wrong; try again.';
}
