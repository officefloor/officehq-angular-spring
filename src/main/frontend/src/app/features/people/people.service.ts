import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim } from './claims.service';

export type Grade = 'A' | 'B' | 'C';

export const GRADES: readonly Grade[] = ['A', 'B', 'C'];

export interface Person {
  id: number;
  name: string;
  grade: Grade;
  /** Raised each time the person is saved. */
  version: number;
  /** Retired as soon as a retirement date is set, whatever the date. */
  retired: boolean;
  /** The date the person is retired from (YYYY-MM-DD); null while they are not retired. */
  retiredFrom: string | null;
  /** On hold: their OPEN claims count in no live total while held. */
  held: boolean;
}

/** A patron a person has named: another person on the same sheet. */
export interface Patron {
  id: number;
  personId: number;
  patronId: number;
}

/** What a quick-add made: the person the claim is for (added just now when the name was new) and
 *  the DRAFT claim. */
export interface QuickAdded {
  person: Person;
  claim: Claim;
}

export interface PersonFields {
  name: string;
  grade: Grade;
}

// The people kept on a sheet, read from and written to the server.
@Injectable({ providedIn: 'root' })
export class PeopleService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheet: string): Promise<Person[]> {
    return firstValueFrom(
      this.http.get<Person[]>(this.url(sheet), { headers: this.session.headers() }),
    );
  }

  add(sheet: string, fields: PersonFields): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(this.url(sheet), fields, { headers: this.session.headers() }),
    );
  }

  /** Save the person as edited from `version`; refused with 409 and the person as now stored
   *  (`current`) when someone else has saved them since. */
  update(sheet: string, id: number, version: number, fields: PersonFields): Promise<Person> {
    return firstValueFrom(
      this.http.put<Person>(`${this.url(sheet)}/${id}`, { ...fields, version }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Retire the person from `from` (the sheet's today when null). */
  retire(sheet: string, id: number, from: string | null): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheet)}/${id}/retire`, { from }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Put the person on hold (`held` true) or take them off it; refused (409) when they already are. */
  hold(sheet: string, id: number, held: boolean): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheet)}/${id}/hold`, { held }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** The sheet's patrons, in the order they were named. */
  patrons(sheet: string): Promise<Patron[]> {
    return firstValueFrom(
      this.http.get<Patron[]>(`${this.url(sheet)}/patrons`, { headers: this.session.headers() }),
    );
  }

  /** Name `patronId` as the person's patron; refused (409) for the person themselves or one already
   *  named. */
  addPatron(sheet: string, id: number, patronId: number): Promise<Patron> {
    return firstValueFrom(
      this.http.post<Patron>(`${this.url(sheet)}/${id}/patrons`, { patronId }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Add a DRAFT claim of `amount`, dated the sheet's today, for the person named `name`, adding
   *  them with grade A first when no one on the sheet has that name; all or nothing. */
  quickAdd(sheet: string, name: string, amount: string): Promise<QuickAdded> {
    return firstValueFrom(
      this.http.post<QuickAdded>(`/api/sheets/${encodeURIComponent(sheet)}/quick-add`, { name, amount }, {
        headers: this.session.headers(),
      }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/people`;
  }
}
