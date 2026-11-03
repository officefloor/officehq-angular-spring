import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { Claim } from './claims';
import { Session } from './session';

export type Grade = 'A' | 'B' | 'C';

/** A grade a person was given, from a day (yyyy-mm-dd); null: from the beginning of time. */
export interface GradeFrom {
  grade: Grade;
  from: string | null;
}

/** The grade in force on a day: the one with the latest from-date on or before it, or else the one
 *  with no from-date; grades come earliest first. */
export function gradeOn(grades: GradeFrom[], day: string | null): Grade | undefined {
  let on: Grade | undefined;
  for (const g of grades) {
    if (g.from === null || (day !== null && g.from <= day)) on = g.grade;
  }
  return on;
}

/** A hold on a person from a day (null: from the beginning of time) to a day (null: open-ended),
 *  both included (yyyy-mm-dd). */
export interface Hold {
  from: string | null;
  to: string | null;
}

/** What leaves a person's open claims, unbound by a statement, out of the live totals: their
 *  holds, and their retirement once the sheet's today reaches it. */
export interface Leave {
  holds: Hold[];
  retiredFrom: string | null;
}

/** Whether a person retired from a day (null: not retired) is retired on another. */
export function retiredOn(retiredFrom: string | null, day: string | null): boolean {
  return retiredFrom !== null && day !== null && retiredFrom <= day;
}

/** Whether one of the holds covers a day. */
export function heldOn(holds: Hold[], day: string): boolean {
  return holds.some((h) => (h.from === null || h.from <= day) && (h.to === null || day <= h.to));
}

/** A past retirement of a person: from the day they were retired from to the day they were
 *  unretired (yyyy-mm-dd). */
export interface Retirement {
  from: string;
  to: string;
}

/** A statement of a person that stands: its date (yyyy-mm-dd) and their due-total in it (two
 *  decimals; null when it was n/a). */
export interface StatementLine {
  date: string;
  dueTotal: string | null;
}

/** A snapshot of a person: the day being left (yyyy-mm-dd) and their due-total on it (two
 *  decimals, or n/a). */
export interface Snapshot {
  day: string;
  dueTotal: string;
}

export interface Person {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the person was read. */
  grade: Grade;
  /** Every grade recorded for them, earliest first. */
  grades: GradeFrom[];
  /** Raised each time the person is saved. */
  version: number;
  /** How many statements were taken of them. */
  statementCount: number;
  /** The last statement's due-total as text with two decimals; null when it was n/a or none was taken. */
  statedTotal: string | null;
  /** The day (yyyy-mm-dd) the last statement was taken; null when none was. */
  statementDate: string | null;
  /** Whether they are retired: they are as soon as a retirement day is set, whatever the day. */
  retired: boolean;
  /** The day (yyyy-mm-dd) they are retired from; null when they are not retired. */
  retiredFrom: string | null;
  /** Their past retirements, earliest first. */
  retirements: Retirement[];
  /** Whether they have any hold, whatever its days. */
  held: boolean;
  /** Their holds, earliest first: their open claims dated within one count in no live total. */
  holds: Hold[];
  /** Their own terms in force on the sheet's today in days, ahead of their grade's and the sheet's;
   *  null when they have none in force. */
  terms: number | null;
  /** Their own undated terms (from the beginning of time); null when none. */
  undatedTerms: number | null;
  /** Their own terms recorded from a day, earliest first. */
  datedTerms: DatedTerms[];
  /** Their contact, trimmed and lower-cased; null when they have none. */
  contact: string | null;
  /** Their aliases, sorted as names are compared. */
  aliases: string[];
  /** Whether their name or an alias is used as a name or alias on another sheet of the sheet's
   *  tree (its ancestors and descendants): every edit of them is refused until it renames them
   *  clear of the clash. */
  clashing: boolean;
  /** The sheet they were transferred from; null when they were not transferred. */
  origin: string | null;
  /** Their tags, sorted and comma and space separated; empty when they have none. */
  tags: string;
  /** Their snapshots, oldest first. */
  snapshots: Snapshot[];
  /** Their statements that stand, oldest first by date (carried ones among them). */
  statements: StatementLine[];
  /** The reason of their flag while it is in force on the sheet's today; null when none is. */
  flag: string | null;
}

/** A statement just taken: the person as they now show, and their claims (newest first) as it
 *  bound them. */
export interface Taken {
  person: Person;
  claims: Claim[];
}

/** A person's own terms recorded from a day; terms null: none from then. */
export interface DatedTerms {
  terms: number | null;
  from: string;
}

export interface PersonForm {
  name: string;
  grade: Grade;
  /** The day (yyyy-mm-dd) an edit's grade applies from; null takes the sheet's today. */
  gradeFrom?: string | null;
  /** Their own terms: a whole number of days; blank for none. */
  terms: string;
  /** The day (yyyy-mm-dd) an edit's terms apply from; null records them from the beginning of time. */
  termsFrom?: string | null;
  /** Their contact; blank for none. */
  contact: string;
  /** Comma separated tags (the store trims, lower-cases and drops repeats); empty for none. */
  tags?: string;
}

// The people kept on the active sheet.
@Injectable({ providedIn: 'root' })
export class People {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Promise<Person[]> {
    return firstValueFrom(this.http.get<Person[]>(this.url(sheetId)));
  }

  add(form: PersonForm): Promise<Person> {
    const { sheetId, user } = this.active();
    return firstValueFrom(this.http.post<Person>(this.url(sheetId), form, { headers: { 'X-User': user } }));
  }

  /** Save over the version of the person the editor loaded; refused (412) when another user has
   *  saved them since. */
  edit(id: number, version: number, form: PersonForm): Promise<Person> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.put<Person>(`${this.url(sheetId)}/${id}`, { ...form, version }, { headers: { 'X-User': user } }),
    );
  }

  /** Import people from lines of name,grade,terms,contact, each added, or (when the name matches a
   *  person on the sheet) updated, as the form would; all or none. Answers how many were added and
   *  how many updated, and those people as they now show, in line order. */
  importPeople(text: string): Promise<{ added: number; updated: number; people: Person[] }> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<{ added: number; updated: number; people: Person[] }>(`${this.url(sheetId)}/import`, { text },
        { headers: { 'X-User': user } }),
    );
  }

  /** Give the people a grade from a day (null: the sheet's today), each as an edit would; answers
   *  how many were changed and those people as they now show. */
  grade(personIds: number[], grade: Grade, from: string | null): Promise<{ graded: number; people: Person[] }> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<{ graded: number; people: Person[] }>(`${this.url(sheetId)}/grade`, { personIds, grade, from },
        { headers: { 'X-User': user } }),
    );
  }

  /** Retire the people from a day (null: the sheet's today), each as a row's retire would, in list
   *  order; answers how many were retired and those people as they now show. */
  retireAll(personIds: number[], from: string | null): Promise<{ retired: number; people: Person[] }> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<{ retired: number; people: Person[] }>(`${this.url(sheetId)}/retire`, { personIds, from },
        { headers: { 'X-User': user } }),
    );
  }

  /** Give the person an alias; refused when it equals any name or alias on the sheet. */
  addAlias(id: number, alias: string): Promise<Person> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/aliases`, { alias }, { headers: { 'X-User': user } }),
    );
  }

  /** Merge the person into another; answers with the other as they now stand. */
  merge(id: number, intoId: number): Promise<Person> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/merge`, { intoId }, { headers: { 'X-User': user } }),
    );
  }

  /** Transfer the person to another sheet by its id; they leave this sheet. */
  transfer(id: number, toSheet: string): Promise<void> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<void>(`${this.url(sheetId)}/${id}/transfer`, { toSheet }, { headers: { 'X-User': user } }),
    );
  }

  /** Undo the transfer that brought the person to this sheet today; they go back to where they came
   *  from and leave this sheet. */
  transferUndo(id: number): Promise<void> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<void>(`${this.url(sheetId)}/${id}/transfer-undo`, {}, { headers: { 'X-User': user } }),
    );
  }

  /** Swap the person with the one above (up) or below (down) them in the list; answers the people
   *  in their new list order. Refused at the top moving up and at the bottom moving down. */
  move(id: number, direction: 'up' | 'down'): Promise<Person[]> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Person[]>(`${this.url(sheetId)}/${id}/move`, { direction }, { headers: { 'X-User': user } }),
    );
  }

  /** Retire the person from a day; null takes the sheet's today. */
  retire(id: number, from: string | null): Promise<Person> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/retire`, { from }, { headers: { 'X-User': user } }),
    );
  }

  /** Unretire the person on the sheet's today; refused when they are not retired. */
  unretire(id: number): Promise<Person> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/unretire`, {}, { headers: { 'X-User': user } }),
    );
  }

  /** Put the person on hold from a day (null: the sheet's today) to a day (null: open-ended). */
  hold(id: number, from: string | null, to: string | null): Promise<Person> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/hold`, { from, to }, { headers: { 'X-User': user } }),
    );
  }

  /** Flag the person (a steward only) with a reason until a day (included; null: the sheet's today). */
  flag(id: number, reason: string, until: string | null): Promise<Person> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Person>(`${this.url(sheetId)}/${id}/flag`, { reason, until }, { headers: { 'X-User': user } }),
    );
  }

  /** Take a statement of the person as of a day (null: the sheet's today): their due-total then is
   *  recorded and each claim of theirs that counted then is bound to the figure it had then. */
  statement(id: number, asOf: string | null): Promise<Taken> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Taken>(`${this.url(sheetId)}/${id}/statements`, { asOf }, { headers: { 'X-User': user } }),
    );
  }

  /** Cancel the person's last statement (a steward only): the claims it bound become live again. */
  cancelStatement(id: number): Promise<Taken> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.delete<Taken>(`${this.url(sheetId)}/${id}/statements/last`, { headers: { 'X-User': user } }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }

  private active(): { sheetId: string; user: string } {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return { sheetId, user };
  }
}
