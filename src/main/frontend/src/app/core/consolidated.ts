import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** A sheet's descendants' figures added up (not the sheet's own): how many people (each counted once, those whose
 *  names or aliases match as names are compared being one), and the due-total and overdue-total of all their people
 *  trimmed once of the highest and lowest (two decimals); each of those people's names and figures; per grade, the due-totals of the people not
 *  retired on it (two decimals; a grade no one counts on is missing); and per claim status, how many claims are in
 *  it (a status no claim is in is missing); how many claims that count are overdue on their sheet's today; and the
 *  settled-total, open-total and cut total of all their people, each trimmed once as the due-total (two decimals). */
export interface DescendantFigures {
  peopleCount: number;
  dueTotal: string;
  overdueTotal: string;
  dueByGrade: Record<string, string>;
  countByStatus: Record<string, number>;
  people: TreePerson[];
  overdueCount: number;
  settledTotal: string;
  openTotal: string;
  cutTotal: string;
}

/** A person of the tree: their name and aliases, their due-total (null with none), overdue-total, settled-total,
 *  open-total and cut total as their sheet shows them (two decimals). */
export interface TreePerson {
  names: string[];
  dueTotal: string | null;
  overdueTotal: string;
  settledTotal: string;
  openTotal: string;
  cutTotal: string;
}

/** How many distinct people these are: those sharing a name or alias, as names are compared (trimmed, case
 *  ignored), directly or through others, being one. */
export function distinctPeople(people: { names: string[] }[]): number {
  const parent = people.map((_, i) => i);
  const root = (i: number): number => (parent[i] === i ? i : (parent[i] = root(parent[i])));
  const firstWithName = new Map<string, number>();
  let distinct = people.length;
  people.forEach((p, i) => {
    for (const name of p.names) {
      const key = name.trim().toLowerCase();
      const other = firstWithName.get(key);
      if (other === undefined) {
        firstWithName.set(key, i);
        continue;
      }
      const a = root(i), b = root(other);
      if (a !== b) {
        parent[a] = b;
        distinct--;
      }
    }
  });
  return distinct;
}

/** No descendants: nothing to add to a sheet's own figures. */
export const NO_DESCENDANTS: DescendantFigures = {
  peopleCount: 0, dueTotal: '0.00', overdueTotal: '0.00', dueByGrade: {}, countByStatus: {}, people: [], overdueCount: 0,
  settledTotal: '0.00', openTotal: '0.00', cutTotal: '0.00',
};

// A sheet's descendants (its children, the sheets naming it as their parent, and theirs and so on), whose figures
// the screen adds to the sheet's own to show them consolidated.
@Injectable({ providedIn: 'root' })
export class Consolidated {
  private readonly http = inject(HttpClient);

  /** With `self`, the sheet's own live figures are added in too. */
  descendants(sheetId: string, self = false): Observable<DescendantFigures> {
    return this.http.get<DescendantFigures>(`/api/sheets/${encodeURIComponent(sheetId)}/descendants/figures`,
      self ? { params: { self: 'true' } } : {});
  }
}

/** Whether a sheet exists (has ever been loaded), and per grade the due-totals of the people not retired on it, as
 *  its summary shows them (two decimals; a grade no one counts on is missing). */
export interface GradeDues {
  exists: boolean;
  dueByGrade: Record<string, string>;
}

// A sheet's per-grade due-totals, to compare the shown sheet's summary with.
@Injectable({ providedIn: 'root' })
export class SheetGradeDues {
  private readonly http = inject(HttpClient);

  of(sheetId: string): Observable<GradeDues> {
    return this.http.get<GradeDues>(`/api/sheets/${encodeURIComponent(sheetId)}/grade-dues`);
  }
}
