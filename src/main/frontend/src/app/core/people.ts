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

/** A hold on a person from its from-date to its to-date (yyyy-mm-dd), both inclusive; a null to-date has no end. */
export interface HoldView {
  from: string;
  to: string | null;
}

export interface PersonView {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the person was read. */
  grade: Grade;
  /** Every grade recorded, in the order they take effect. */
  grades: GradeFrom[];
  /** The person's own terms in days, ahead of their grade's and the sheet's; null when they have none. */
  terms: number | null;
  /** Raised on every save; an edit names the version it was made from. */
  version: number;
  /** Retired as soon as a retirement date is set, whatever the date. */
  retired: boolean;
  /** The date the person is retired from (yyyy-mm-dd); null while not retired. */
  retiredFrom: string | null;
  /** Whether the person has any hold, whatever its dates. */
  held: boolean;
  /** The person's holds, in the order they start: their open claims dated within one, and not bound by a
   *  statement, count in no live total. */
  holds: HoldView[];
  /** The person's contact, trimmed and lower-cased; null when they have none. */
  contact: string | null;
  /** The other names the person is found by, sorted as names are compared; missing in an older snapshot. */
  aliases?: string[] | null;
  /** Whether the person's name or an alias is used on another sheet of the sheet's tree; missing in an older
   *  snapshot. */
  clashing?: boolean;
  /** The sheet the person was transferred from; null or missing for anyone never transferred. */
  origin?: string | null;
  /** The retirements the person was unretired from, in the order they started; missing in an older snapshot. */
  pastRetirements?: RetirementView[] | null;
  /** The person's tags, sorted; empty when they have none. */
  tags: string[];
}

/** A past retirement: retired from `from`, unretired on `to` (yyyy-mm-dd). */
export interface RetirementView {
  from: string;
  to: string;
}

export interface PersonForm {
  name: string;
  grade: Grade;
  /** The person's own terms in days; null: none. */
  terms: number | null;
  /** The person's contact; blank: none. */
  contact: string;
  /** The tags, comma separated (each trimmed and lower-cased by the store, repeats dropped); blank for none. */
  tags: string;
}

/** An edit of a person: the grade is recorded from `gradeFrom` (yyyy-mm-dd; null takes the sheet's today). */
export interface PersonEdit extends PersonForm {
  gradeFrom: string | null;
}

/** What a bulk grade did: how many people it changed, and those people as saved. */
export interface BulkGradeView {
  changed: number;
  people: PersonView[];
}

/** What a bulk retire did: how many people it retired, and those people as saved. */
export interface BulkRetireView {
  retired: number;
  people: PersonView[];
}

/** What an import did: how many people it added and updated, and those people, in line order. */
export interface ImportView {
  added: number;
  updated: number;
  people: PersonView[];
}

/** The person's grade in force on `date` (yyyy-mm-dd): the one with the latest from-date on or before it. */
export function gradeOn(p: Pick<PersonView, 'grade' | 'grades'>, date: string): Grade {
  let inForce: GradeFrom | undefined;
  for (const g of p.grades) {
    if ((g.from === null || g.from <= date) && (inForce === undefined || (inForce.from ?? '') <= (g.from ?? ''))) inForce = g;
  }
  return inForce?.grade ?? p.grade;
}

// The people kept on a sheet, listed in their positions; new people go to the end.
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

  /** Add a person per pasted line of name,grade,terms,contact; when any line is refused, none is added. */
  importPeople(sheetId: string, text: string): Observable<ImportView> {
    return this.http.post<ImportView>(`${this.base(sheetId)}/import`, { text });
  }

  /** Save an edit made from `version` of the person; refused (412) when someone has saved them since. */
  update(sheetId: string, id: number, version: number, person: PersonEdit): Observable<PersonView> {
    return this.http.put<PersonView>(`${this.base(sheetId)}/${id}`, { ...person, version });
  }

  /** Retire the person from `from` (yyyy-mm-dd); null takes the sheet's today. */
  retire(sheetId: string, id: number, from: string | null): Observable<PersonView> {
    return this.http.post<PersonView>(`${this.base(sheetId)}/${id}/retire`, { from });
  }

  /** Unretire a retired person on the sheet's today; refused (409) for anyone not retired. */
  unretire(sheetId: string, id: number): Observable<PersonView> {
    return this.http.post<PersonView>(`${this.base(sheetId)}/${id}/unretire`, {});
  }

  /** Swap the person with the one above or below them: everyone on the sheet, in list order, as moved. */
  move(sheetId: string, id: number, direction: 'up' | 'down'): Observable<PersonView[]> {
    return this.http.post<PersonView[]>(`${this.base(sheetId)}/${id}/move`, { direction });
  }

  /** Put the person on hold from `from` (null takes the sheet's today) to `to` (null: no end). */
  hold(sheetId: string, id: number, from: string | null, to: string | null): Observable<PersonView> {
    return this.http.post<PersonView>(`${this.base(sheetId)}/${id}/hold`, { from, to });
  }

  /** Give the person an alias; refused when blank or already a name or alias on the sheet. */
  addAlias(sheetId: string, id: number, alias: string): Observable<PersonView> {
    return this.http.post<PersonView>(`${this.base(sheetId)}/${id}/aliases`, { alias });
  }

  /** Merge the person into another (a steward only): the person merged into, as saved. */
  merge(sheetId: string, id: number, intoId: number): Observable<PersonView> {
    return this.http.post<PersonView>(`${this.base(sheetId)}/${id}/merge`, { intoId });
  }

  /** Transfer the person to another sheet (a steward only); they leave this one. */
  transfer(sheetId: string, id: number, toSheetId: string): Observable<void> {
    return this.http.post<void>(`${this.base(sheetId)}/${id}/transfer`, { toSheetId });
  }

  /** Give the selected people `grade` from `from` (yyyy-mm-dd; null takes the sheet's today), in list order. */
  bulkGrade(sheetId: string, ids: readonly number[], grade: Grade, from: string | null): Observable<BulkGradeView> {
    return this.http.post<BulkGradeView>(`${this.base(sheetId)}/bulk-grade`, { ids, grade, from });
  }

  /** Retire the selected people from `from` (yyyy-mm-dd; null takes the sheet's today), in list order. */
  bulkRetire(sheetId: string, ids: readonly number[], from: string | null): Observable<BulkRetireView> {
    return this.http.post<BulkRetireView>(`${this.base(sheetId)}/bulk-retire`, { ids, from });
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }
}
