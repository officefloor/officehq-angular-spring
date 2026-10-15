import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Person {
  id: number;
  name: string;
  alpha: number | null;
}

export interface NewPerson {
  name: string;
  alpha: number;
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
}
