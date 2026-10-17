import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Person {
  id: number;
  name: string;
  alpha: number | null;
  beta: number | null;
  gamma: number | null;
  hold: boolean;
  patronIds: number[];
  // alpha times beta; null (n/a) when either is blank.
  thrust: number | null;
  // thrust plus three times (gamma minus alpha); null (n/a) when thrust or gamma is.
  momentum: number | null;
  // momentum times two; null (n/a) when momentum is.
  scaled: number | null;
  // scaled divided by three, rounded down (also below zero); null (n/a) when scaled is.
  third: number | null;
}

@Injectable({ providedIn: 'root' })
export class PeopleService {
  private readonly http = inject(HttpClient);

  list(): Observable<Person[]> {
    return this.http.get<Person[]>('/api/people');
  }
}
