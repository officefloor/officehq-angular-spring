import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ClaimView } from './claims';

export const GRADES = ['A', 'B', 'C'] as const;
export type Grade = (typeof GRADES)[number];

export interface PersonView {
  id: number;
  name: string;
  grade: Grade;
  /** Raised on every save of the person. */
  version: number;
  /** How many statements have been taken for the person. */
  statementCount: number;
  /** The last statement's due-total with two decimals; null when it was n/a, or none was taken. */
  statementTotal: string | null;
  /** The sheet's today (yyyy-mm-dd) when the last statement was taken; null when none was. */
  statementDate: string | null;
  /** The date (yyyy-mm-dd) the person is retired from; null when they are not retired. */
  retiredFrom: string | null;
  /** Whether the person is on hold: their OPEN claims count in no live total. */
  held: boolean;
  /** The ids of the other people on the sheet the person has named as their patrons. */
  patronIds: number[];
}

/** A statement taken: the person as stated and their claims, newest first. */
export interface StatementView {
  person: PersonView;
  claims: ClaimView[];
}

export interface PersonForm {
  name: string;
  grade: Grade;
  /** On an edit, the version of the person the form loaded. */
  version?: number;
}

// The people kept on a sheet, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class PeopleApi {
  private readonly http = inject(HttpClient);

  /** The sheet's people in the order they were added. */
  list(sheet: string): Promise<PersonView[]> {
    return firstValueFrom(this.http.get<PersonView[]>(this.url(sheet)));
  }

  add(sheet: string, form: PersonForm): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(this.url(sheet), form));
  }

  edit(sheet: string, id: number, form: PersonForm): Promise<PersonView> {
    return firstValueFrom(this.http.put<PersonView>(`${this.url(sheet)}/${id}`, form));
  }

  /** Take a statement: record the person's due-total and bind their counting claims to their figures. */
  statement(sheet: string, id: number): Promise<StatementView> {
    return firstValueFrom(this.http.post<StatementView>(`${this.url(sheet)}/${id}/statements`, null));
  }

  /** Retire a person from a date; with none, from the sheet's today. */
  retire(sheet: string, id: number, from: string | null): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/retire`, { from }));
  }

  /** Put a person on hold, or take them off it. */
  hold(sheet: string, id: number, held: boolean): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/${held ? 'hold' : 'release'}`, null));
  }

  /** Name another person on the sheet as a patron of this one. */
  addPatron(sheet: string, id: number, patronId: number): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/patrons`, { patronId }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/people`;
  }
}
