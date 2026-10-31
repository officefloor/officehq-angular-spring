import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';

export type Grade = 'A' | 'B' | 'C';
export const GRADES: readonly Grade[] = ['A', 'B', 'C'];

/** A grade recorded for a person, in force from its from-date (yyyy-mm-dd) until the next one's; the
 *  first grade a person was given has none: it applies from the beginning of time. */
export interface GradeFrom {
  grade: Grade;
  from: string | null;
}

export interface PersonView {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the person was read. */
  grade: Grade;
  /** Every grade recorded, in the order they take effect. */
  grades: GradeFrom[];
  /** Raised on every save; an edit names the version it was made from. */
  version: number;
  /** Retired as soon as a retirement date is set, whatever the date. */
  retired: boolean;
  /** The date the person is retired from (yyyy-mm-dd); null while not retired. */
  retiredFrom: string | null;
  /** On hold: their OPEN claims count in no live total; their SETTLED ones still do. */
  held: boolean;
}

export interface PersonForm {
  name: string;
  grade: Grade;
}

/** An edit of a person: the grade is recorded from `gradeFrom` (yyyy-mm-dd; null takes the sheet's today). */
export interface PersonEdit extends PersonForm {
  gradeFrom: string | null;
}

/** The person's grade in force on `date` (yyyy-mm-dd): the one with the latest from-date on or before it. */
export function gradeOn(p: Pick<PersonView, 'grade' | 'grades'>, date: string): Grade {
  let inForce: GradeFrom | undefined;
  for (const g of p.grades) {
    if ((g.from === null || g.from <= date) && (inForce === undefined || (inForce.from ?? '') <= (g.from ?? ''))) inForce = g;
  }
  return inForce?.grade ?? p.grade;
}

// The people kept on a sheet, listed in the order they were added.
@Injectable({ providedIn: 'root' })
export class People {
  private readonly http = inject(HttpClient);
  /** Whether retired people are listed: belongs to the screen, so loading a sheet leaves it as it is. */
  readonly showRetired = signal(false);

  list(sheetId: string): Observable<PersonView[]> {
    return this.http.get<PersonView[]>(this.base(sheetId));
  }

  add(sheetId: string, person: PersonForm): Observable<PersonView> {
    return this.http.post<PersonView>(this.base(sheetId), person);
  }

  /** Save an edit made from `version` of the person; refused (412) when someone has saved them since. */
  update(sheetId: string, id: number, version: number, person: PersonEdit): Observable<PersonView> {
    return this.http.put<PersonView>(`${this.base(sheetId)}/${id}`, { ...person, version });
  }

  /** Retire the person from `from` (yyyy-mm-dd); null takes the sheet's today. */
  retire(sheetId: string, id: number, from: string | null): Observable<PersonView> {
    return this.http.post<PersonView>(`${this.base(sheetId)}/${id}/retire`, { from });
  }

  /** Put the person on hold, or take them off hold when held. */
  toggleHold(sheetId: string, id: number): Observable<PersonView> {
    return this.http.post<PersonView>(`${this.base(sheetId)}/${id}/hold`, {});
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }
}
