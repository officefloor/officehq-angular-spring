import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

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

  // Adds a person to a sheet; null is the unnamed sheet.
  add(sheet: string | null, person: NewPerson): Observable<Person> {
    return this.http.post<Person>('/api/people', person, { params: sheetParams(sheet) });
  }

  update(id: number, values: Omit<NewPerson, 'name'>): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, values);
  }

  setAlpha(id: number, alpha: number | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { alpha });
  }

  setBeta(id: number, beta: number | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { beta });
  }

  setGamma(id: number, gamma: number | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { gamma });
  }

  setPatrons(id: number, patronIds: number[]): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { patronIds });
  }
}

function sheetParams(sheet: string | null): HttpParams {
  return sheet === null ? new HttpParams() : new HttpParams().set('sheet', sheet);
}
