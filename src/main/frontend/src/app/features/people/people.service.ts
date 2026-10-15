import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, throwError } from 'rxjs';

export interface Person {
  id: number;
  name: string;
  alpha: number | null;
  beta: number | null;
  gamma: number | null;
  thrust: number | null;
  drift: number | null;
  momentum: number | null;
  tier: string | null;
  patrons: Patron[];
  backing: number | null;
  net: number | null;
}

export interface Patron {
  id: number;
  name: string;
}

export interface NewPerson {
  name: string;
  alpha: number | null;
  beta: number | null;
  gamma: number | null;
}

// The people REST API (/api/people).
@Injectable({ providedIn: 'root' })
export class PeopleService {
  private readonly http = inject(HttpClient);

  // The people on a sheet; null is the unnamed sheet.
  list(sheet: string | null): Observable<Person[]> {
    return this.http.get<Person[]>('/api/people', { params: sheetParams(sheet) });
  }

  // Whether a named sheet is stored; false when no sheet has that id.
  sheetExists(sheet: string): Observable<boolean> {
    return this.http.get(`/api/sheets/${encodeURIComponent(sheet)}`).pipe(
      map(() => true),
      catchError((error: HttpErrorResponse) => (error.status === 404 ? of(false) : throwError(() => error))),
    );
  }

  // Adds a person to a sheet; null is the unnamed sheet.
  add(sheet: string | null, person: NewPerson): Observable<Person> {
    return this.http.post<Person>('/api/people', person, { params: sheetParams(sheet) });
  }

  // Updates the person with an id on a sheet (ids are per sheet); null is the unnamed sheet.
  update(sheet: string | null, id: number, values: Omit<NewPerson, 'name'>): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, values, { params: sheetParams(sheet) });
  }

  setAlpha(sheet: string | null, id: number, alpha: number | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { alpha }, { params: sheetParams(sheet) });
  }

  setBeta(sheet: string | null, id: number, beta: number | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { beta }, { params: sheetParams(sheet) });
  }

  setGamma(sheet: string | null, id: number, gamma: number | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { gamma }, { params: sheetParams(sheet) });
  }

  setPatrons(sheet: string | null, id: number, patronIds: number[]): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { patronIds }, { params: sheetParams(sheet) });
  }
}

function sheetParams(sheet: string | null): HttpParams {
  return sheet === null ? new HttpParams() : new HttpParams().set('sheet', sheet);
}
