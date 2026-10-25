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

  /** Add a draft claim to the named person, adding them with grade A first when the name is new;
   *  refused as a whole, adding no one, when the claim is refused. */
  quickAdd(sheetId: string, form: QuickAddForm): Promise<QuickAddView> {
    return firstValueFrom(this.http.post<QuickAddView>(`/api/sheets/${encodeURIComponent(sheetId)}/quick-add`, form));
  }
}
