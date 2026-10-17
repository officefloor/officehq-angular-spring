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
  // scaled divided by three, rounded to the nearest (a half up); null (n/a) when scaled is.
  third: number | null;
  // scaled minus the sheet's average scaled (n/a ones and people on hold skipped, rounded to the nearest); null (n/a) when scaled is.
  norm: number | null;
  // the patrons' momentum added up (n/a ones add nothing); null (n/a) when momentum is.
  backing: number | null;
  // scaled divided by four, rounded to the nearest (a half up); null (n/a) when scaled is.
  quarter: number | null;
  // SINK below zero, STEADY from 0 up to 199, SURGE at 200 or more; null (n/a) when scaled is.
  tier: 'SINK' | 'STEADY' | 'SURGE' | null;
  // scaled plus momentum plus norm; null (n/a) when momentum or norm is.
  keel: number | null;
  // keel plus momentum; null (n/a) when keel is.
  fathom: number | null;
  // keel plus thrust; null (n/a) when keel is.
  draft: number | null;
  // fathom plus scaled; null (n/a) when fathom is.
  cascade: number | null;
  // keel minus thrust; null (n/a) when keel is.
  trim: number | null;
  // cascade plus keel; null (n/a) when cascade is.
  ripple: number | null;
  // keel plus norm; null (n/a) when keel or norm is.
  heading: number | null;
  // ripple plus thrust; null (n/a) when ripple is.
  ballast: number | null;
  // ballast plus norm; null (n/a) when ballast or norm is.
  keystone: number | null;
  // keel minus norm; null (n/a) when keel or norm is.
  bearing: number | null;
  // keel plus momentum; null (n/a) when keel is.
  pitch: number | null;
  // keel minus momentum; null (n/a) when keel is.
  tackle: number | null;
}

@Injectable({ providedIn: 'root' })
export class PeopleService {
  private readonly http = inject(HttpClient);

  list(): Observable<Person[]> {
    return this.http.get<Person[]>('/api/people');
  }
}
