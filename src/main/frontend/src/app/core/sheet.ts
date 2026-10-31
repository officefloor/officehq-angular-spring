import { HttpClient, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { Grade } from './people';

/** A dated setting's value from its from-date (yyyy-mm-dd; null: from the beginning of time) until the next one's
 *  of the same key; a null value means none. Keys: clerk-limit, floor, terms, terms-A/B/C, late-pct, grace-A/B/C, cap, proof-threshold, proof-threshold-A/B/C,
 *  note-threshold. */
export interface DatedSetting {
  key: string;
  from: string | null;
  value: string | null;
}

/** The sheet as the screen shows it: each dated setting as it is in force on its today. */
export interface SheetView {
  id: string;
  /** The sheet's today (yyyy-mm-dd): every date-dependent decision and default uses it, never the clock. */
  today: string;
  /** A clerk can settle a claim only when its due is at or below this (two decimals); null: no limit. */
  clerkLimit: string | null;
  /** A claim's live due is at least this (two decimals). */
  floor: string;
  /** The sheet's terms in business days: a claim is due by the terms-th business day after its date. */
  terms: number;
  /** The sheet's late percent (two decimals): an overdue claim's due gains this percent of its figure
   *  for every full 30 days it is overdue. */
  latePct: string;
  /** The sheet's policy date (yyyy-mm-dd): a claim dated before it rounds half up, one on or after it half to
   *  even; null when it is the sheet's today. */
  policyDate: string | null;
  /** The sheet's period numbering from (yyyy-mm-dd): while its today is on or after it, a claim opened is
   *  numbered within its period; null when off. */
  periodNumberingFrom: string | null;
  /** The terms in days set per grade; a grade without its own uses the sheet's terms. */
  gradeTerms: Partial<Record<Grade, number>>;
  /** The grace days set per grade, in force on the sheet's today; a grade without any is missing. */
  gradeGrace: Partial<Record<Grade, number>>;
  /** A person's due-total is at most this (two decimals); null: none. */
  cap: string | null;
  /** A claim whose due is above this (two decimals), in force on its date, cannot be settled or approved while it
   *  has no proofs; null: none. */
  proofThreshold: string | null;
  /** The proof threshold (two decimals) set per grade, in force on the sheet's today; a grade without its own uses
   *  the sheet's. */
  gradeProofThreshold: Partial<Record<Grade, string>>;
  /** A claim whose due is above this (two decimals), in force on its date, cannot be opened while its note is
   *  empty; null: none. */
  noteThreshold: string | null;
  /** The number of the last settle batch (a bulk settle that settled at least one claim); null when there has been none. */
  settleBatch: number | null;
  /** Every dated value recorded for the settings that carry a figure. */
  settings: DatedSetting[];
  /** The permission matrix entries set: for an action, the roles that may take it (comma separated); an action
   *  missing keeps its standing rule. Not dated. */
  permissions: Partial<Record<PermAction, string>>;
  /** The sheet's grace days: when its today changes, every open period whose to-date plus these is before the new
   *  today is closed; null: off. Not dated. */
  graceDays: number | null;
  /** The pot (two decimals), cut among the members of each balanced pool that is a member of none; null when there is none. Not dated. */
  pot: string | null;
  /** The id of the sheet's parent sheet: a grade with no rate in force on a date on this sheet takes its parent's
   *  (then its parent's parent's, up to three levels up); null when it has none. Not dated. */
  parent: string | null;
  /** Whether the sheet is archived: everything on it stays visible and every change to it is refused. */
  archived: boolean;
  /** The date (yyyy-mm-dd) the sheet is shown as of: it is as it stood at the end of that day, which stands in for
   *  its today; null when it is shown as it is now. */
  asOf: string | null;
  /** The sheet's entry date (yyyy-mm-dd): while set, a new claim given no date takes it in place of the sheet's
   *  today; null: off. Not dated. */
  entryDate: string | null;
}

/** The actions the permission matrix covers. */
export const PERM_ACTIONS = ['open', 'settle', 'void', 'reassign', 'close', 'reopen'] as const;
export type PermAction = (typeof PERM_ACTIONS)[number];

/** The roles that may take each action while its matrix entry is blank. */
export const STANDING_PERMISSIONS: Record<PermAction, string> = {
  open: 'clerk,steward', settle: 'clerk,steward', void: 'clerk,steward',
  reassign: 'steward', close: 'clerk,steward', reopen: 'steward',
};

/** A setting's value in force on a date (yyyy-mm-dd): the one recorded with the latest from-date on or before it;
 *  `start` (the sheet's starting value) before every from-date, or when it is none. */
export function settingOn(settings: readonly DatedSetting[], key: string, date: string, start: string): string {
  let inForce: DatedSetting | undefined;
  for (const s of settings) {
    if (s.key === key && (s.from === null || s.from <= date) && (inForce === undefined || inForce.from === null
        || (s.from !== null && s.from > inForce.from))) inForce = s;
  }
  return inForce?.value ?? start;
}

/** The grace days of a grade in force on a date (yyyy-mm-dd): a claim dated then whose person is of that grade
 *  then is overdue only once its due-by plus these is before the sheet's today; 0 when none is set. */
export function graceOn(settings: readonly DatedSetting[], grade: Grade, date: string): number {
  const value = settingOn(settings, `grace-${grade}`, date, '');
  return value === '' ? 0 : Number(value);
}

/** The terms a sheet has until they are changed. */
export const DEFAULT_TERMS = 14;

// The active sheet. Everything else in the app is kept per sheet; loading an id re-reads it from
// the store, and an id never loaded before starts an empty sheet.
@Injectable({ providedIn: 'root' })
export class Sheets {
  private readonly http = inject(HttpClient);
  private readonly active = signal<SheetView | null>(null);
  readonly current = this.active.asReadonly();
  /** The sheet as last (re)loaded: set on each load only, so changing its today re-reads nothing. */
  private readonly lastLoad = signal<SheetView | null>(null);
  readonly loaded = this.lastLoad.asReadonly();
  /** The date (yyyy-mm-dd) this screen shows the sheet as of; null: as it is now. Belongs to the screen, so loading a
   *  sheet leaves it as it is; every read then answers as the sheet stood at the end of it, and every change is refused. */
  private readonly asOfDate = signal<string | null>(null);
  readonly asOf = this.asOfDate.asReadonly();

  /** Show the sheet as of a date (null: as it is now), re-loading it so everything on screen is read that way. */
  setAsOf(id: string, asOf: string | null): Observable<SheetView> {
    this.asOfDate.set(asOf);
    return this.load(id);
  }

  load(id: string): Observable<SheetView> {
    return this.http
      .post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null)
      .pipe(
        tap((s) => {
          this.active.set(s);
          this.lastLoad.set(s);
        }),
      );
  }

  /** Note a settle batch just made on the active sheet as its last. */
  settleBatched(batch: number): void {
    this.active.update((s) => (s ? { ...s, settleBatch: batch } : s));
  }

  /** Archive the active sheet (a steward only). */
  archive(id: string): Observable<SheetView> {
    return this.http
      .post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/archive`, null)
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's today. */
  setToday(id: string, today: string): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record the active sheet's clerk limit from a date (null: its today); a null clerk limit means no limit. */
  setSettings(id: string, clerkLimit: string | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings`, { clerkLimit, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record the active sheet's floor from a date (null: its today). */
  setFloor(id: string, floor: string, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/floor`, { floor, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record the active sheet's terms (days) from a date (null: its today). */
  setTerms(id: string, terms: number, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/terms`, { terms, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record a grade's own terms (days) from a date (null: the sheet's today); null leaves the grade on the sheet's terms. */
  setGradeTerms(id: string, grade: Grade, terms: number | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/terms/${grade}`, { terms, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record a grade's grace days from a date (null: the sheet's today); null means none. */
  setGradeGrace(id: string, grade: Grade, days: number | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/grace/${grade}`, { days, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's policy date; null means the sheet's today. */
  setPolicyDate(id: string, policyDate: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/policy-date`, { policyDate })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's entry date; null means off. Not dated. */
  setEntryDate(id: string, entryDate: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/entry-date`, { entryDate })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's period numbering from; null means off. */
  setPeriodNumberingFrom(id: string, periodNumberingFrom: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/period-numbering-from`, { periodNumberingFrom })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's grace days; null means off. Not dated. */
  setGraceDays(id: string, graceDays: number | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/grace-days`, { graceDays })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's pot; null means none. Not dated. */
  setPot(id: string, pot: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/pot`, { pot })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's parent sheet; null means none. Not dated. */
  setParent(id: string, parent: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/parent`, { parent })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record the active sheet's late percent from a date (null: its today). */
  setLatePct(id: string, latePct: string, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/late-pct`, { latePct, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record a grade's own proof threshold from a date (null: the sheet's today); null means the sheet's. */
  setGradeProofThreshold(id: string, grade: Grade, proofThreshold: string | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/proof-threshold/${grade}`, { proofThreshold, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record the active sheet's proof threshold from a date (null: its today); null means none. */
  setProofThreshold(id: string, proofThreshold: string | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/proof-threshold`, { proofThreshold, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record the active sheet's note threshold from a date (null: its today); null means none. */
  setNoteThreshold(id: string, noteThreshold: string | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/note-threshold`, { noteThreshold, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record the active sheet's cap from a date (null: its today); null means none. */
  setCap(id: string, cap: string | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/cap`, { cap, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Set the roles that may take an action (comma separated); blank keeps its standing rule. Not dated. */
  setPermission(id: string, action: PermAction, roles: string): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/perm/${action}`, { roles })
      .pipe(tap((s) => this.active.set(s)));
  }
}

// While the screen shows the sheet as of a date, every API call carries it in the X-As-Of header.
export const asOfHeader: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Sheets).asOf();
  return next(asOf ? req.clone({ setHeaders: { 'X-As-Of': asOf } }) : req);
};
