import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

// A person's workflow state, moved forward from DRAFT to REVIEW to FINAL; a FINAL person may be
// reopened to REWORK and moved forward to FINAL again.
export type State = 'DRAFT' | 'REVIEW' | 'FINAL' | 'REWORK';

export interface Person {
  id: number;
  name: string;
  alpha: number | null;
  beta: number | null;
  gamma: number | null;
  delta: number | null;
  epsilon: number | null;
  zeta: number | null;
  grade: Grade | null;
  weight: number | null;
  kind: Kind | null;
  verge: number | null;
  thrust: number | null;
  drift: number | null;
  momentum: number | null;
  force: number | null;
  sway: number | null;
  torque: number | null;
  pivot: number | null;
  blend: number | null;
  flux: number | null;
  crest: number | null;
  swing: number | null;
  haul: number | null;
  phase: string | null;
  load: number | null;
  tier: string | null;
  patrons: Patron[];
  backing: number | null;
  net: number | null;
  rank: number | null;
  percentile: number | null;
  onset: string | null;
  age: number | null;
  decay: number | null;
  active: boolean;
  state: State;
  effective: number | null;
  clout: number | null;
  influence: number | null;
  brace: number | null;
  strain: number | null;
  regions: string[];
  reach: number | null;
  gust: number | null;
  coil: number | null;
  allies: Ally[];
  allyCount: number;
  synergy: number | null;
  combined: number | null;
  score: number | null;
  noteSum: number;
  quality: number | null;
  adjusted: number | null;
  band: string | null;
  scaled: number | null;
  thrum: number | null;
  spool: number | null;
  drag: string | null;
  lift: number | null;
  yield: number | null;
  grip: number | null;
  norm: number | null;
  outlier: boolean;
}

// A sheet: its id (null for the unnamed sheet) and its anchor date (yyyy-mm-dd; today when none is set).
export interface Sheet {
  id: string | null;
  anchor: string;
}

export type Grade = 'LOW' | 'MID' | 'HIGH';

export const GRADES: readonly Grade[] = ['LOW', 'MID', 'HIGH'];

export type Kind = 'A' | 'B' | 'C';

export const KINDS: readonly Kind[] = ['A', 'B', 'C'];

export interface Patron {
  id: number;
  name: string;
}

// An ally: a person this person follows; following is one-directional.
export interface Ally {
  id: number;
  name: string;
}

export interface NewPerson {
  name: string;
  alpha: number | null;
  beta: number | null;
  gamma: number | null;
  delta: number | null;
  epsilon: number | null;
  zeta: number | null;
  grade: Grade | null;
  kind: Kind | null;
  regions: string[];
}

// The people REST API (/api/people).
@Injectable({ providedIn: 'root' })
export class PeopleService {
  private readonly http = inject(HttpClient);

  // The people on a sheet; null is the unnamed sheet.
  list(sheet: string | null): Observable<Person[]> {
    return this.http.get<Person[]>('/api/people', { params: sheetParams(sheet) });
  }

  // A sheet (with its anchor); null is the unnamed sheet.
  sheet(sheet: string | null): Observable<Sheet> {
    return this.http.get<Sheet>('/api/sheet', { params: sheetParams(sheet) });
  }

  // Opens a named sheet, starting a new empty one when no sheet has that id yet.
  openSheet(sheet: string): Observable<void> {
    return this.http.put(`/api/sheets/${encodeURIComponent(sheet)}`, null).pipe(map(() => undefined));
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

  setDelta(sheet: string | null, id: number, delta: number | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { delta }, { params: sheetParams(sheet) });
  }

  setEpsilon(sheet: string | null, id: number, epsilon: number | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { epsilon }, { params: sheetParams(sheet) });
  }

  setZeta(sheet: string | null, id: number, zeta: number | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { zeta }, { params: sheetParams(sheet) });
  }

  setGrade(sheet: string | null, id: number, grade: Grade | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { grade }, { params: sheetParams(sheet) });
  }

  setKind(sheet: string | null, id: number, kind: Kind | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { kind }, { params: sheetParams(sheet) });
  }

  setRegions(sheet: string | null, id: number, regions: string[]): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { regions }, { params: sheetParams(sheet) });
  }

  setActive(sheet: string | null, id: number, active: boolean): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { active }, { params: sheetParams(sheet) });
  }

  // Moves a person forward to their next state (DRAFT -> REVIEW -> FINAL, REWORK -> FINAL).
  advance(sheet: string | null, id: number): Observable<Person> {
    return this.http.post<Person>(`/api/people/${id}/advance`, null, { params: sheetParams(sheet) });
  }

  // Reopens a FINAL person to REWORK.
  rework(sheet: string | null, id: number): Observable<Person> {
    return this.http.post<Person>(`/api/people/${id}/rework`, null, { params: sheetParams(sheet) });
  }

  // Moves a person back to their previous state (REVIEW -> DRAFT); refused once FINAL.
  demote(sheet: string | null, id: number): Observable<Person> {
    return this.http.post<Person>(`/api/people/${id}/demote`, null, { params: sheetParams(sheet) });
  }

  setPatrons(sheet: string | null, id: number, patronIds: number[]): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { patronIds }, { params: sheetParams(sheet) });
  }

  // Sets a person's whole-number score; null clears it.
  setScore(sheet: string | null, id: number, score: number | null): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { score }, { params: sheetParams(sheet) });
  }

  // Sets the people a person follows (their allies) by id; it does not make them follow back.
  setAllies(sheet: string | null, id: number, allyIds: number[]): Observable<Person> {
    return this.http.patch<Person>(`/api/people/${id}`, { allyIds }, { params: sheetParams(sheet) });
  }
}

function sheetParams(sheet: string | null): HttpParams {
  return sheet === null ? new HttpParams() : new HttpParams().set('sheet', sheet);
}
