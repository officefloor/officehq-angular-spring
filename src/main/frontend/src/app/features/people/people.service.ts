import { HttpClient } from '@angular/common/http';
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
  patronId: number | null;
  patronName: string | null;
  backing: number | null;
  net: number | null;
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

  list(): Observable<Person[]> {
    return this.http.get<Person[]>('/api/people');
  }

  add(person: NewPerson): Observable<Person> {
    return this.http.post<Person>('/api/people', person);
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

  setPatron(id: number, patronId: number | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { patronId });
  }
}
