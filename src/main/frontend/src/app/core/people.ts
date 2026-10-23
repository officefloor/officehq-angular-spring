import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ClaimView } from './claims';

/** The grades every sheet has; a sheet's own list (see GradesStore) adds those added to it. */
export const GRADES: readonly string[] = ['A', 'B', 'C'];
/** A grade: one to three capital letters. */
export type Grade = string;

/** A grade a person has, from a date (yyyy-mm-dd); null from the beginning of time. */
export interface GradeView {
  grade: Grade;
  from: string | null;
}

/**
 * The grade a person has in force on a date (yyyy-mm-dd): the one recorded from the latest date on or
 * before it, else the first they were given (from the beginning of time).
 */
export function gradeOn(grades: GradeView[], date: string): Grade {
  let on = grades[0];
  for (const g of grades) {
    if (g.from === null || g.from > date) continue;
    if (on.from === null || g.from > on.from) on = g;
  }
  return on.grade;
}

/** Own terms a person has from a date (yyyy-mm-dd), in days; null for none of their own. */
export interface TermsView {
  terms: number | null;
  from: string;
}

/**
 * The own terms a person has in force on a date (yyyy-mm-dd): those recorded from the latest date on
 * or before it, else those from the beginning of time; null for none.
 */
export function termsOn(person: PersonView, date: string): number | null {
  let on: TermsView | null = null;
  for (const t of person.termsFrom ?? []) {
    if (t.from <= date && (on === null || t.from > on.from)) on = t;
  }
  return on === null ? person.terms : on.terms;
}

export interface PersonView {
  id: number;
  name: string;
  /** The person's grades by the date each is in force from, the first from the beginning of time. */
  grades: GradeView[];
  /** The person's own terms in days from the beginning of time; null when they have none and take their grade's terms. */
  terms: number | null;
  /** The own terms recorded since, by the date each is in force from; absent from a view kept before terms were dated. */
  termsFrom?: TermsView[];
  /** The person's contact, trimmed and lower-cased; null when they have none. */
  contact: string | null;
  /** The person's tags, sorted (trimmed, lower case, no repeats); empty (or absent, as kept by an old snapshot) for none. */
  tags?: string[] | null;
  /** Every tag the person has ever carried, sorted; empty (or absent, as kept by an old snapshot) for none. */
  tagsUsed?: string[] | null;
  /** Raised on every save of the person. */
  version: number;
  /** How many statements have been taken for the person. */
  statementCount: number;
  /** The last statement's due-total with two decimals; null when it was n/a, or none was taken. */
  statementTotal: string | null;
  /** The date (yyyy-mm-dd) the last statement was taken as of; null when none was. */
  statementDate: string | null;
  /** The date (yyyy-mm-dd) the person is retired from; null when they are not retired. */
  retiredFrom: string | null;
  /** The person's past retirements, in the order they were unretired from; absent from a view kept before unretiring was. */
  retirements?: RetirementView[];
  /** Whether the person has any hold, whatever its dates. */
  held: boolean;
  /** The person's holds in the order they were added: a person held on the sheet's today gets no pool cut. */
  holds: HoldView[];
  /** The ids of the other people on the sheet the person has named as their patrons. */
  patronIds: number[];
  /** The other names the person goes by; absent from a view kept before aliases were. */
  aliases?: string[];
  /** Whether the person's name or an alias is in use on another sheet of the sheet's tree; absent from a view kept before this was. */
  clashing?: boolean;
  /** Why a steward flagged the person; null when never, absent from a view kept before flags were. */
  flagReason?: string | null;
  /** The last day (yyyy-mm-dd) the person's flag is in force; null when never, absent from a view kept before flags were. */
  flagUntil?: string | null;
  /** The user name that may approve or reject the person's PENDING claims; null when none, absent from a view kept before approvers were. */
  approver?: string | null;
  /** The sheet the person was last transferred from; null when never, absent from a view kept before this was. */
  originSheetId?: string | null;
  /** The person's snapshot lines, oldest first; absent from a view kept before snapshot lines were. */
  snapshots?: SnapshotView[];
  /** The statements still standing for the person, oldest first; absent from a view kept before statement lines were. */
  statementLines?: StatementLineView[];
}

/** A statement line of a person: the date (yyyy-mm-dd) it was taken as of and its due-total ('n/a' when no rate applied). */
export interface StatementLineView {
  date: string;
  dueTotal: string;
}

/** A snapshot line of a person: the day being left (yyyy-mm-dd) and their due-total on it ('n/a' when no rate applied). */
export interface SnapshotView {
  asOf: string;
  dueTotal: string;
}

/** A past retirement of a person, from its date to the day they were unretired (yyyy-mm-dd). */
export interface RetirementView {
  from: string;
  to: string;
}

/** A hold on a person, from a date to a date (yyyy-mm-dd, both included); to is null with no end. */
export interface HoldView {
  from: string;
  to: string | null;
}

/** The reason a person's flag shows on a date (yyyy-mm-dd): '' unless it is in force then. */
export function flagOn(person: PersonView, date: string): string {
  return person.flagReason && person.flagUntil && date <= person.flagUntil ? person.flagReason : '';
}

/** Whether a date (yyyy-mm-dd) falls within any of a person's holds. */
export function heldOn(person: PersonView, date: string): boolean {
  return person.holds.some((h) => h.from <= date && (h.to === null || date <= h.to));
}

/** Whether a person's retirement date is reached on the sheet's today (yyyy-mm-dd). */
export function retiredOn(person: PersonView, today: string): boolean {
  return person.retiredFrom !== null && today !== '' && person.retiredFrom <= today;
}

/**
 * Whether a person's OPEN or PENDING claim, unbound by a statement, is left out of the live totals on the
 * sheet's today: once their retirement date is reached. A hold (the person's or the claim's) takes no claim
 * out of any total; it only takes the person out of pool cuts.
 */
export function leavesLive(person: PersonView, today: string): boolean {
  return retiredOn(person, today);
}

/** A statement taken: the person as stated and their claims, newest first. */
export interface StatementView {
  person: PersonView;
  claims: ClaimView[];
}

export interface PersonForm {
  name: string;
  grade: Grade;
  /** On an edit, the date (yyyy-mm-dd) the grade is in force from; null for the sheet's today. */
  gradeFrom?: string | null;
  /** The person's own terms in days; null for none. */
  terms: number | null;
  /** On an edit, the date (yyyy-mm-dd) the terms are in force from; null for the beginning of time. */
  termsFrom?: string | null;
  /** The person's contact as typed; blank for none. */
  contact: string;
  /** On an edit, whether the terms were typed on the form: PERSON_EDITED then carries them. */
  termsGiven?: boolean;
  /** On an edit, whether the contact was typed on the form: PERSON_EDITED then carries it. */
  contactGiven?: boolean;
  /** On an edit, whether the grade was chosen on the form: it is then recorded from its date even when already in force. */
  gradeGiven?: boolean;
  /** On an edit, the version of the person the form loaded. */
  version?: number;
  /** The person's tags, comma separated; blank for none. */
  tags?: string;
}

// The people kept on a sheet, read from and written to the store.
/** The people an import added and those it updated, each in the order of their lines. */
export interface ImportView {
  added: PersonView[];
  updated: PersonView[];
}

@Injectable({ providedIn: 'root' })
export class PeopleApi {
  private readonly http = inject(HttpClient);

  /** The sheet's people in list order: by their position. */
  list(sheet: string): Promise<PersonView[]> {
    return firstValueFrom(this.http.get<PersonView[]>(this.url(sheet)));
  }

  add(sheet: string, form: PersonForm): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(this.url(sheet), form));
  }

  /**
   * Add a person per pasted line of name,grade,terms,contact, in order, each as the form would, or update
   * the person a line names; refused (400 or 409) as a whole.
   */
  importPeople(sheet: string, text: string): Promise<ImportView> {
    return firstValueFrom(this.http.post<ImportView>(`${this.url(sheet)}/import`, { text }));
  }

  edit(sheet: string, id: number, form: PersonForm): Promise<PersonView> {
    return firstValueFrom(this.http.put<PersonView>(`${this.url(sheet)}/${id}`, form));
  }

  /** Record the grade chosen on a person's row from the sheet's today, from the version the row shows. */
  grade(sheet: string, id: number, grade: Grade, version: number): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/grade`, { grade, version }));
  }

  /**
   * Give the selected people a grade from a date (null: the sheet's today) together, each as the person
   * form would, in list order: the people changed.
   */
  gradeAll(sheet: string, personIds: number[], grade: Grade, from: string | null): Promise<PersonView[]> {
    return firstValueFrom(this.http.post<PersonView[]>(`${this.url(sheet)}/grade`, { personIds, grade, from }));
  }

  /**
   * Take a statement as of a date on or before the sheet's today (null: now): record the person's
   * due-total and bind the claims that counted then to their figures as they stood then.
   */
  statement(sheet: string, id: number, asOf: string | null = null): Promise<StatementView> {
    return firstValueFrom(this.http.post<StatementView>(`${this.url(sheet)}/${id}/statements`, { asOf }));
  }

  /** Cancel the last statement: the claims it bound are live again, the one before it shown. */
  unstate(sheet: string, id: number): Promise<StatementView> {
    return firstValueFrom(this.http.delete<StatementView>(`${this.url(sheet)}/${id}/statements/last`));
  }

  /** Swap a person with the one above (up) or below (down) them in the list. */
  move(sheet: string, id: number, direction: 'up' | 'down'): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/move/${direction}`, null));
  }

  /** Retire a person from a date; with none, from the sheet's today. */
  /**
   * Retire the selected people from a date (null: the sheet's today) together, each as the row's retire
   * would, in list order: the people retired.
   */
  retireAll(sheet: string, personIds: number[], from: string | null): Promise<PersonView[]> {
    return firstValueFrom(this.http.post<PersonView[]>(`${this.url(sheet)}/retire`, { personIds, from }));
  }

  retire(sheet: string, id: number, from: string | null): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/retire`, { from }));
  }

  /** Unretire a retired person on the sheet's today. */
  unretire(sheet: string, id: number): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/unretire`, null));
  }

  /** Flag a person with a reason until a date (yyyy-mm-dd); a steward only. */
  flag(sheet: string, id: number, reason: string, until: string): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/flag`, { reason, until }));
  }

  /** Set the user name that may approve or reject a person's PENDING claims; blank for none. */
  setApprover(sheet: string, id: number, user: string): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/approver`, { user }));
  }

  /**
   * Hold the selected people from a date (null: the sheet's today) to a date (null: no end) together,
   * each as the row's hold would, in list order: the people held.
   */
  holdAll(sheet: string, personIds: number[], from: string | null, to: string | null): Promise<PersonView[]> {
    return firstValueFrom(this.http.post<PersonView[]>(`${this.url(sheet)}/hold`, { personIds, from, to }));
  }

  /** Put a person on hold from a date (none: the sheet's today) to a date (none: no end). */
  hold(sheet: string, id: number, from: string | null, to: string | null): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/holds`, { from, to }));
  }

  /** Name another person on the sheet as a patron of this one. */
  addPatron(sheet: string, id: number, patronId: number): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/patrons`, { patronId }));
  }

  /** Give a person an alias: it must not equal, as names are compared, any name or alias on the sheet. */
  addAlias(sheet: string, id: number, alias: string): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/aliases`, { alias }));
  }

  /** Transfer a person to another sheet by its id (a steward only): they leave this sheet. */
  transfer(sheet: string, id: number, toSheetId: string): Promise<void> {
    return firstValueFrom(this.http.post<void>(`${this.url(sheet)}/${id}/transfer`, { toSheetId }));
  }

  /**
   * Undo the transfer that brought a person to this sheet on its today (a steward only): they go back
   * to the sheet they came from.
   */
  transferUndo(sheet: string, id: number): Promise<void> {
    return firstValueFrom(this.http.post<void>(`${this.url(sheet)}/${id}/transfer-undo`, {}));
  }

  /**
   * Undo the last merge into a person on the sheet (only the steward who made it, on the same
   * sheet-today): the person merged into, after it.
   */
  mergeUndo(sheet: string, id: number): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/merge-undo`, {}));
  }

  /** Merge a person into another on the sheet (a steward only): the person merged into, after it. */
  merge(sheet: string, id: number, intoId: number): Promise<PersonView> {
    return firstValueFrom(this.http.post<PersonView>(`${this.url(sheet)}/${id}/merge`, { intoId }));
  }

  /** The ids of the people on the sheet the signed-in user watches. */
  watched(sheet: string): Promise<number[]> {
    return firstValueFrom(this.http.get<number[]>(`${this.url(sheet)}/watched`));
  }

  /** Start (true) or stop (false) watching a person as the signed-in user. */
  watch(sheet: string, id: number, on: boolean): Promise<void> {
    const url = `${this.url(sheet)}/${id}/watch`;
    return firstValueFrom(on ? this.http.put<void>(url, null) : this.http.delete<void>(url));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/people`;
  }
}
