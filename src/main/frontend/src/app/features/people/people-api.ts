import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

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
}
