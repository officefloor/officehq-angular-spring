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
  // alpha times beta, a blank one counting as zero.
  thrust: number | null;
  // thrust plus three times (gamma minus alpha), a blank one counting as zero.
  momentum: number | null;
  // momentum times two; null (n/a) when momentum is.
  scaled: number | null;
  // scaled divided by three, rounded to the nearest (a half up); null (n/a) when scaled is.
  third: number | null;
  // scaled minus the sheet's average scaled (n/a ones and people on hold skipped, rounded to the nearest); null (n/a) when scaled is.
  norm: number | null;
  // the patrons' momentum added up (patronage is mutual, each pair counted once) (n/a ones add nothing); null (n/a) when momentum is.
  backing: number | null;
  // scaled divided by four, rounded to the nearest (a half up); null (n/a) when scaled is.
  quarter: number | null;
  // SINK below zero, STEADY from 0 up to 199, SURGE at 200 or more; null (n/a) when scaled is.
  tier: 'SINK' | 'STEADY' | 'SURGE' | null;
  // scaled plus twice momentum plus twice norm plus thrust; null (n/a) when momentum or norm is.
  keel: number | null;
  // keel plus momentum plus 68; null (n/a) when keel is.
  fathom: number | null;
  // keel plus thrust; null (n/a) when keel is.
  draft: number | null;
  // fathom plus scaled plus 72; null (n/a) when fathom is.
  cascade: number | null;
  // keel minus thrust; null (n/a) when keel is.
  trim: number | null;
  // cascade plus keel plus 76; null (n/a) when cascade is.
  ripple: number | null;
  // keel plus norm; null (n/a) when keel or norm is.
  heading: number | null;
  // ripple plus thrust plus 80; null (n/a) when ripple is.
  ballast: number | null;
  // ballast plus norm; null (n/a) when ballast or norm is.
  keystone: number | null;
  // keel minus norm; null (n/a) when keel or norm is.
  bearing: number | null;
  // keel plus momentum; null (n/a) when keel is.
  pitch: number | null;
  // keel minus momentum; null (n/a) when keel is.
  tackle: number | null;
  // keel plus scaled; null (n/a) when keel is.
  yaw: number | null;
  // keel minus scaled; null (n/a) when keel is.
  heave: number | null;
  // keel plus 200; null (n/a) when keel is.
  camber: number | null;
  // keel minus 100; null (n/a) when keel is.
  sheer: number | null;
  // keel times two; null (n/a) when keel is.
  beam: number | null;
  // keel divided by two, rounded to the nearest (a half up); null (n/a) when keel is.
  freeboard: number | null;
  // keel times three; null (n/a) when keel is.
  buoyancy: number | null;
  // keel divided by three, rounded to the nearest (a half up); null (n/a) when keel is.
  knot: number | null;
  // keel plus gamma, a blank gamma counting as zero; null (n/a) when keel is.
  cable: number | null;
  // keel minus gamma, a blank gamma counting as zero; null (n/a) when keel is.
  hull: number | null;
  // keel plus fathom; null (n/a) when keel is.
  bow: number | null;
  // keel minus fathom; null (n/a) when keel is.
  stern: number | null;
  // keel plus ripple; null (n/a) when keel or ripple is.
  helm: number | null;
  // keel minus ripple; null (n/a) when keel or ripple is.
  rudder: number | null;
  // keel plus 2000; null (n/a) when keel is.
  mast: number | null;
  // keel divided by four, rounded to the nearest (a half up); null (n/a) when keel is.
  rigging: number | null;
  // keel plus keystone; null (n/a) when keel or keystone is.
  moor: number | null;
  // keel minus keystone; null (n/a) when keel or keystone is.
  berth: number | null;
  // keel times five; null (n/a) when keel is.
  wharf: number | null;
  // keel divided by ten, rounded to the nearest (a half up); null (n/a) when keel is.
  jetty: number | null;
  // keel plus 500; null (n/a) when keel is.
  pier: number | null;
  // keel minus 500; null (n/a) when keel is.
  ebb: number | null;
  // keel plus ballast; null (n/a) when keel or ballast is.
  flood: number | null;
  // keel minus ballast; null (n/a) when keel or ballast is.
  swell: number | null;
}

@Injectable({ providedIn: 'root' })
export class PeopleService {
  private readonly http = inject(HttpClient);

  list(): Observable<Person[]> {
    return this.http.get<Person[]>('/api/people');
  }
}
