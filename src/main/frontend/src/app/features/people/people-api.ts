import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ClaimView } from '../claims/claims-api';

export const GRADES = ['A', 'B', 'C'] as const;
export type Grade = (typeof GRADES)[number];

/** A grade a person was given, in force from its from-date until their next grade. */
export interface GradeView {
  grade: Grade;
  /** The date the grade is in force from (yyyy-mm-dd), or null when it applies from the beginning of time. */
  from: string | null;
}

/** Of a person's grades, the one in force on a date: the one with the latest from-date on or before it. */
export function gradeOn(grades: readonly GradeView[], date: string): Grade | null {
  let inForce: GradeView | null = null;
  for (const g of grades) {
    if (g.from !== null && g.from > date) continue;
    if (inForce === null || (inForce.from ?? '') < (g.from ?? '')) inForce = g;
  }
  return inForce?.grade ?? null;
}

export interface PersonView {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the store answered. */
  grade: Grade;
  /** Every grade the person was given, the one from the beginning of time first, then by from-date. */
  grades: GradeView[];
  /** Bumped on each save; an edit is sent with the version its editor loaded. */
  version: number;
  /** True as soon as a retirement date is set, whatever the date. */
  retired: boolean;
  /** The date the person is retired from, or null while they are not retired. */
  retiredFrom: string | null;
  /** Whether the person is on hold: while held, their open claims count in no live total. */
  held: boolean;
  /** The ids of the other people on the sheet the person has named as patrons, in the order named. */
  patronIds: number[];
}

/** A new person's fields: their grade applies from the beginning of time. */
export interface PersonForm {
  name: string;
  grade: Grade;
}

/** An edited person's fields: the grade is given from `gradeFrom`, null meaning the sheet's today. */
export interface PersonEdit extends PersonForm {
  gradeFrom: string | null;
  version: number;
}

/** What the quick-add bar sends: a person's name and a claim's amount. */
export interface QuickAddForm {
  name: string;
  amount: string;
}

/** The person a quick-add claim went to (added first when the name was new), and the claim. */
export interface QuickAddView {
  person: PersonView;
  claim: ClaimView;
}

// The people on a sheet, as the store holds them.
@Injectable({ providedIn: 'root' })
export class PeopleApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }

  list(sheetId: string): Promise<PersonView[]> {
    return firstValueFrom(this.http.get<PersonView[]>(this.url(sheetId)));
  }

  add(sheetId: string, form: PersonForm): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(this.url(sheetId), form));
  }

  /** Refused with 412 when someone has saved the person since `form.version` was loaded. */
  update(sheetId: string, id: number, form: PersonEdit): Promise<PersonView> {
    return firstValueFrom(this.http.put<PersonView>(`${this.url(sheetId)}/${id}`, form));
  }

  /** Retire the person from a date; null means the sheet's today. */
  retire(sheetId: string, id: number, from: string | null): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheetId)}/${id}/retire`, { from }));
  }

  /** Put the person on hold (true) or take them off it (false); refused when they already are. */
  hold(sheetId: string, id: number, held: boolean): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheetId)}/${id}/${held ? 'hold' : 'release'}`, {}));
  }

  /** Name another person on the sheet as the person's patron; refused for the person themselves and
   *  for someone already their patron. */
  addPatron(sheetId: string, id: number, patronId: number): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheetId)}/${id}/patrons`, { patronId }));
  }

  /** Add a draft claim to the named person, adding them with grade A first when the name is new;
   *  refused as a whole, adding no one, when the claim is refused. */
  quickAdd(sheetId: string, form: QuickAddForm): Promise<QuickAddView> {
    return firstValueFrom(this.http.post<QuickAddView>(`/api/sheets/${encodeURIComponent(sheetId)}/quick-add`, form));
  }
}
