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

/** A hold put on a person, both days included. */
export interface HoldView {
  /** The date the hold starts (yyyy-mm-dd), or null when it holds from the beginning of time. */
  from: string | null;
  /** The date the hold ends (yyyy-mm-dd), or null when it has no end. */
  to: string | null;
}

/** Whether a date (yyyy-mm-dd) falls within one of the holds. */
export function heldOn(holds: readonly HoldView[], date: string): boolean {
  return holds.some((h) => (h.from === null || h.from <= date) && (h.to === null || date <= h.to));
}

/** A past retirement of a person, from its from-date to the day they were unretired (yyyy-mm-dd). */
export interface RetirementView {
  from: string;
  to: string;
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
  /** Every past retirement of the person, in the order they were unretired. */
  retirements: RetirementView[];
  /** Whether the person has any hold, whatever its dates. */
  held: boolean;
  /** Every hold put on the person, by from-date: their open claims dated within one, unless a
   *  statement binds them, count in no live total. */
  holds: HoldView[];
  /** The ids of the other people on the sheet the person has named as patrons, in the order named. */
  patronIds: number[];
  /** Whether the signed-in user watches the person. */
  watched: boolean;
  /** The person's own terms in force on the sheet's today, in days, or null when they use their
   *  grade's, else the sheet's. */
  terms: number | null;
  /** The person's own terms from the beginning of time, in days, or null when they have none. */
  ownTerms: number | null;
  /** The person's contact, trimmed and lower-cased, or null when they have none. */
  contact: string | null;
  /** The other names the person is found by, sorted as names are compared. */
  aliases: string[];
  /** Whether a name or alias of theirs is used on another sheet of the sheet's tree. */
  clashing: boolean;
  /** The sheet the person was transferred from, or null when they were added on this sheet. */
  origin: string | null;
  /** The person's tags, lower-cased and sorted; empty when they have none. */
  tags: string[];
  /** One line per snapshot written for the person, oldest first: the day being left and their due-total. */
  snapshots: string[];
}

/** A new person's fields: their grade applies from the beginning of time; their terms are whole days,
 *  blank for none; a blank contact is none. */
export interface PersonForm {
  name: string;
  grade: Grade;
  terms: string;
  /** The date the terms are recorded from; null records them from the beginning of time. */
  termsFrom: string | null;
  contact: string;
  /** The person's tags, comma separated as typed; the store trims, lower-cases and sorts them, dropping repeats. */
  tags: string;
}

/** An edited person's fields: the grade is given from `gradeFrom`, null meaning the sheet's today. */
export interface PersonEdit extends PersonForm {
  gradeFrom: string | null;
  version: number;
}

/** What a bulk grade did: the people it changed, in list order, and how many. */
export interface BulkGradeView {
  people: PersonView[];
  changed: number;
}

/** What a bulk retire did: the people it retired, in list order, and how many. */
export interface BulkRetireView {
  people: PersonView[];
  retired: number;
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

/** What an import did: how many people it added and updated, and the people, in the order of their lines. */
export interface ImportView {
  added: number;
  updated: number;
  people: PersonView[];
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

  /** Add a person per line of name,grade,terms,contact; refused, adding no one, when any line is. */
  importPeople(sheetId: string, text: string): Promise<ImportView> {
    return firstValueFrom(this.http.post<ImportView>(`${this.url(sheetId)}/import`, { text }));
  }

  /** Refused with 412 when someone has saved the person since `form.version` was loaded. */
  update(sheetId: string, id: number, form: PersonEdit): Promise<PersonView> {
    return firstValueFrom(this.http.put<PersonView>(`${this.url(sheetId)}/${id}`, form));
  }

  /** Give the person the grade from the sheet's today, as the person form would; refused with 412 when
   *  someone has saved the person since `version` was loaded. */
  gradeOne(sheetId: string, id: number, version: number, grade: Grade): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheetId)}/${id}/grade`, { version, grade }));
  }

  /** Give the people of these ids the grade from a date (null: the sheet's today), each in list order
   *  as the person form would. */
  grade(sheetId: string, ids: number[], grade: Grade, from: string | null): Promise<BulkGradeView> {
    return firstValueFrom(this.http.post<BulkGradeView>(`${this.url(sheetId)}/grade`, { ids, grade, from }));
  }

  /** Retire the people of these ids from a date (null: the sheet's today), each in list order as the
   *  row's retire would. */
  retireSelected(sheetId: string, ids: number[], from: string | null): Promise<BulkRetireView> {
    return firstValueFrom(this.http.post<BulkRetireView>(`${this.url(sheetId)}/retire`, { ids, from }));
  }

  /** Swap the person with the one above (up) or below (down) them in the list; refused with 409 for
   *  the top person moving up and the bottom person moving down. */
  move(sheetId: string, id: number, direction: 'up' | 'down'): Promise<void> {
    return firstValueFrom(this.http.post<void>(`${this.url(sheetId)}/${id}/move/${direction}`, {}));
  }

  /** Unretire a retired person on the sheet's today; refused for anyone not retired. */
  unretire(sheetId: string, id: number): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheetId)}/${id}/unretire`, {}));
  }

  /** Retire the person from a date; null means the sheet's today. */
  retire(sheetId: string, id: number, from: string | null): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheetId)}/${id}/retire`, { from }));
  }

  /** Put a hold on the person from a date (null: the sheet's today) to a date (null: no end);
   *  refused when it ends before it starts. */
  hold(sheetId: string, id: number, from: string | null, to: string | null): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheetId)}/${id}/hold`, { from, to }));
  }

  /** Name another person on the sheet as the person's patron; refused for the person themselves and
   *  for someone already their patron. */
  addPatron(sheetId: string, id: number, patronId: number): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheetId)}/${id}/patrons`, { patronId }));
  }

  /** Give the person another name they are found by; refused when blank, or when it equals, as names
   *  are compared, any name or alias on the sheet. */
  addAlias(sheetId: string, id: number, alias: string): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheetId)}/${id}/aliases`, { alias }));
  }

  /** Transfer the person to another sheet by id; a steward only, refused for a retired person, to this
   *  sheet itself, and when their name, an alias or their contact is already on the other sheet. */
  transfer(sheetId: string, id: number, to: string): Promise<void> {
    return firstValueFrom(this.http.post<void>(`${this.url(sheetId)}/${id}/transfer`, { to }));
  }

  /** Merge the person into another; a steward only, refused into the person themselves and when either
   *  is retired. The person merged into is answered. */
  merge(sheetId: string, id: number, intoId: number): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheetId)}/${id}/merge`, { intoId }));
  }

  /** Start (true) or stop (false) the signed-in user watching the person. */
  watch(sheetId: string, id: number, on: boolean): Promise<PersonView> {
    return firstValueFrom(this.http.put<PersonView>(`${this.url(sheetId)}/${id}/watch`, { on }));
  }

  /** Add a draft claim to the named person, adding them with grade A first when the name is new;
   *  refused as a whole, adding no one, when the claim is refused. */
  quickAdd(sheetId: string, form: QuickAddForm): Promise<QuickAddView> {
    return firstValueFrom(this.http.post<QuickAddView>(`/api/sheets/${encodeURIComponent(sheetId)}/quick-add`, form));
  }
}
