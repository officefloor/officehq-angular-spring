import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ClaimView } from '../claims/claims-api';

export const GRADES = ['A', 'B', 'C'] as const;
export type Grade = (typeof GRADES)[number];

export interface PersonView {
  id: number;
  name: string;
  grade: Grade;
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

export interface PersonForm {
  name: string;
  grade: Grade;
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
  update(sheetId: string, id: number, form: PersonForm & { version: number }): Promise<PersonView> {
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
