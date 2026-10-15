import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Person {
  id: number;
  name: string;
  alpha: number | null;
  beta: number | null;
  thrust: number | null;
}

export interface NewPerson {
  name: string;
  alpha: number;
  beta: number | null;
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

  setBeta(id: number, beta: number | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { beta });
  }
}
