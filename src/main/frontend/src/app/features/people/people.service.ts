import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim } from './claims.service';

export type Grade = 'A' | 'B' | 'C';

export const GRADES: readonly Grade[] = ['A', 'B', 'C'];

/** A grade a person was given and the date it is in force from (YYYY-MM-DD); null when it applies
 *  from the beginning of time (the first grade they were given). */
export interface PersonGrade {
  grade: Grade;
  from: string | null;
}

/** How an import of people went: how many it added and how many it updated, and the people it added
 *  followed by those it updated, each in the order of their lines. */
export interface Imported {
  added: number;
  updated: number;
  people: Person[];
}

export interface Person {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the person was read. */
  grade: Grade;
  /** Every grade the person was given, by from-date (the one with none first). */
  grades: PersonGrade[];
  /** Raised each time the person is saved. */
  version: number;
  /** Retired as soon as a retirement date is set, whatever the date. */
  retired: boolean;
  /** The date the person is retired from (YYYY-MM-DD); null while they are not retired. */
  retiredFrom: string | null;
  /** Whether the person has any hold, whatever its dates. */
  held: boolean;
  /** The holds the person has been put on, by from-date: their OPEN and PENDING claims dated within
   *  one count in no live total, unless a statement binds them. */
  holds: PersonHold[];
  /** The person's own terms in days, in place of their grade's and the sheet's; null when none. */
  terms: number | null;
  /** The person's contact, without surrounding spaces and in lower case; null when none. */
  contact: string | null;
  /** The other names the person goes by, sorted as names are compared. */
  aliases: string[];
  /** Whether the person's name or an alias of theirs is used on another sheet of the sheet's tree. */
  clashing: boolean;
  /** The sheet the person was transferred from; null when they were not transferred. */
  origin: string | null;
}

/** A hold a person was put on, from `from` to `to` (YYYY-MM-DD, both included; `to` null: no end). */
export interface PersonHold {
  from: string;
  to: string | null;
}

/** Whether `date` falls within any of the holds. */
export function heldOn(holds: PersonHold[], date: string): boolean {
  return holds.some((h) => h.from <= date && (h.to === null || date <= h.to));
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

/** How a bulk grade went: how many people it changed, and those people as saved. */
export interface BulkGraded {
  graded: number;
  people: Person[];
}

export interface PersonFields {
  name: string;
  grade: Grade;
  /** On a save, the date the grade is given from (YYYY-MM-DD); null for the sheet's today. */
  gradeFrom?: string | null;
  /** The person's own terms in days, as typed; blank for none. */
  terms: string;
  /** The person's contact, as typed; blank for none. */
  contact: string;
}

/** The grade in force on a date: the one with the latest from-date on or before it, or the first
 *  one given when none has; the latest given when there is no date. */
export function gradeOn(grades: PersonGrade[], date: string | null): Grade | null {
  let best: PersonGrade | null = null;
  for (const g of grades) {
    if (date !== null && g.from !== null && g.from > date) continue;
    if (best === null || (best.from ?? '') <= (g.from ?? '')) best = g;
  }
  return best?.grade ?? null;
}

/** A sheet's descendants' figures added up: their people (retired ones included), and each one's
 *  due-total and overdue-total as that sheet shows it, with two decimals. */
export interface DescendantFigures {
  peopleCount: number;
  dueTotal: string;
  overdueTotal: string;
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

  /** The figures of the sheet's descendants (its children, their children, and so on) as they stand,
   *  added up; while the sheet is read as of an earlier date, with the sheet's own as they stand now. */
  descendants(sheet: string): Promise<DescendantFigures> {
    return firstValueFrom(
      this.http.get<DescendantFigures>(`/api/sheets/${encodeURIComponent(sheet)}/descendants`, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Add a person per pasted line of name,grade,terms,contact, in order; when any line is refused
   *  no one is added. */
  importPeople(sheet: string, text: string): Promise<Imported> {
    return firstValueFrom(
      this.http.post<Imported>(`${this.url(sheet)}/import`, { text }, { headers: this.session.headers() }),
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

  /** Give each of the people `grade` from `from` (the sheet's today when null), in list order, each
   *  as the person form would save them. */
  bulkGrade(sheet: string, personIds: number[], grade: Grade, from: string | null): Promise<BulkGraded> {
    return firstValueFrom(
      this.http.post<BulkGraded>(`${this.url(sheet)}/bulk-grade`, { personIds, grade, from }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Swap the person with the one above (`up`) or below them in the list; refused (409) for the
   *  top person moving up and the bottom one moving down. */
  move(sheet: string, id: number, direction: 'up' | 'down'): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheet)}/${id}/move-${direction}`, null, {
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

  /** Put the person on a hold from `from` (the sheet's today when null) to `to` (no end when null);
   *  refused (409) when it ends before it starts. */
  hold(sheet: string, id: number, from: string | null, to: string | null): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheet)}/${id}/hold`, { from, to }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Give the person the alias `alias`; refused (409) when it is blank, or equals a name or alias on
   *  the sheet. */
  addAlias(sheet: string, id: number, alias: string): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheet)}/${id}/aliases`, { alias }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Merge the person into `intoId`, answering the person merged into; refused (409) unless the
   *  signed-in user is a steward, for the person themselves, and when either is retired. */
  merge(sheet: string, id: number, intoId: number): Promise<Person> {
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheet)}/${id}/merge`, { intoId }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Transfer the person to the sheet `toSheet`; refused (409) unless the signed-in user is a
   *  steward, for a retired person, for this sheet's own id, and when their name, an alias or their
   *  contact is already on the other sheet. */
  transfer(sheet: string, id: number, toSheet: string): Promise<void> {
    return firstValueFrom(
      this.http.post<void>(`${this.url(sheet)}/${id}/transfer`, { toSheet }, {
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

  /** The ids of the people the signed-in user watches. */
  watched(sheet: string): Promise<number[]> {
    return firstValueFrom(
      this.http.get<number[]>(`${this.url(sheet)}/watched`, { headers: this.session.headers() }),
    );
  }

  /** Start (`on`) or stop watching the person as the signed-in user; refused (409) when they already
   *  do, or do not. */
  watch(sheet: string, id: number, on: boolean): Promise<Person> {
    const url = `${this.url(sheet)}/${id}/watch`;
    const options = { headers: this.session.headers() };
    return firstValueFrom(
      on ? this.http.post<Person>(url, null, options) : this.http.delete<Person>(url, options),
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
