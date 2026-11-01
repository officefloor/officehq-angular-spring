import { HttpClient, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { GRADES, Grade, StandingGrade } from './people';

/** A dated setting's value from its from-date (yyyy-mm-dd; null: from the beginning of time) until the next one's
 *  of the same key; a null value means none. Keys: clerk-limit, clerk-limit-A/B/C, floor, terms, terms-A/B/C, late-pct, grace-A/B/C, cap, proof-threshold, proof-threshold-A/B/C,
 *  note-threshold, review-threshold, rounding. */
export interface DatedSetting {
  key: string;
  from: string | null;
  value: string | null;
}

/** The sheet as the screen shows it: each dated setting as it is in force on its today. */
/** A policy version: the policy setting whose change made it, and the from-date (yyyy-mm-dd) it was changed from;
 *  null: it counts always. */
export interface PolicyVersion {
  key: string;
  from: string | null;
}

export interface SheetView {
  id: string;
  /** The sheet's today (yyyy-mm-dd): every date-dependent decision and default uses it, never the clock. */
  today: string;
  /** A clerk can settle a claim only when its due is at or below this (two decimals); null: no limit. */
  clerkLimit: string | null;
  /** The clerk limit (two decimals) set per grade, in force on the sheet's today; a grade without its own uses the
   *  sheet's. */
  gradeClerkLimit: Partial<Record<StandingGrade, string>>;
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
  gradeTerms: Partial<Record<StandingGrade, number>>;
  /** The grace days set per grade, in force on the sheet's today; a grade without any is missing. */
  gradeGrace: Partial<Record<StandingGrade, number>>;
  /** A person's due-total is at most this (two decimals); null: none. */
  cap: string | null;
  /** A claim whose due is above this (two decimals), in force on its date, cannot be settled or approved while it
   *  has no proofs; null: none. */
  proofThreshold: string | null;
  /** The proof threshold (two decimals) set per grade, in force on the sheet's today; a grade without its own uses
   *  the sheet's. */
  gradeProofThreshold: Partial<Record<StandingGrade, string>>;
  /** A claim whose due is above this (two decimals), in force on its date, cannot be opened while its note is
   *  empty; null: none. */
  noteThreshold: string | null;
  /** A claim whose due is above this (two decimals), in force on its date, goes to REVIEW when opened instead of
   *  OPEN, until a steward reviews it; null: none. */
  reviewThreshold: string | null;
  /** The rounding mode (half-up or half-even) in force on the sheet's today: a claim dated while one is in force
   *  rounds every live figure by it, one with none by the policy date; null: none. */
  rounding: string | null;
  /** The number of the last settle batch (a bulk settle that settled at least one claim); null when there has been none. */
  settleBatch: number | null;
  /** Every dated value recorded for the settings that carry a figure. */
  settings: DatedSetting[];
  /** The current policy version: how many changes have been made to the policy settings (the rounding mode, the
   *  floor, the cap, the terms, the late percent and the clerk limit); 0 before the first. */
  policyVersion: number;
  /** The policy versions, in the order made (version n first made by the n-th change). */
  policyVersions: PolicyVersion[];
  /** The permission matrix entries set: for an action, the roles that may take it (comma separated); an action
   *  missing keeps its standing rule. Not dated. */
  permissions: Partial<Record<PermAction, string>>;
  /** The sheet's grace days: when its today changes, every open period whose to-date plus these is before the new
   *  today is closed; null: off. Not dated. */
  graceDays: number | null;
  /** The sheet's escalation gap, in days: reminding a claim whose previous reminder was at least these days before
   *  the sheet's today escalates it (once per claim); null: off. Not dated. */
  escalateAfter: number | null;
  /** The sheet's review SLA, in days: a REVIEW claim whose review day (the sheet's today when it went to REVIEW)
   *  plus these is before the sheet's today is late for review; null: none. Not dated. */
  reviewSla: number | null;
  /** The sheet's pending SLA, in days: a PENDING claim whose pending day (the sheet's today when it last went to
   *  PENDING) plus these is before the sheet's today is late pending; null: none. Not dated. */
  pendingSla: number | null;
  /** The sheet's maximum of open claims per person, a whole number: opening a claim is refused when its person
   *  already has this many OPEN or PENDING claims; null: none. Not dated. */
  maxOpen: number | null;
  /** The sheet's score thresholds, whole numbers: while both are set each person shows a band (HIGH at or above the
   *  high one, MID at or above the mid one, else LOW); null: none. Not dated. */
  scoreMid: number | null;
  scoreHigh: number | null;
  /** The sheet's score log switch: 'on' while a change of today records each person's score; null: off. Not dated. */
  scoreLog: 'on' | null;
  /** The sheet's fiscal year start month, 1 to 12: a fiscal year runs twelve months from the first of it and is
   *  named FY<yyyy> by the calendar year it starts in. Not dated. */
  fiscalStartMonth: number;
  /** The pot (two decimals), cut among the members of each balanced pool that is a member of none; null when there is none. Not dated. */
  pot: string | null;
  /** The sheet's proof kinds, comma separated (receipt, photo): while set, a claim above the proof threshold needs a
   *  proof of each before it can be settled or approved; null when there are none. Not dated. */
  proofKinds: string | null;
  /** The sheet's default grade (one of its grades): the grade the quick-add bar gives a new person, and the one an imported
   *  line with a blank grade takes; null when it sets none (then A). Not dated. */
  defaultGrade: string | null;
  /** The grades retired on the sheet, in order: none can be given to a new person nor chosen in an edit; people on
   *  one keep it. Not dated. */
  retiredGrades: string[];
  /** The sheet's grades: A, B and C, then those added to it, in order. Not dated. */
  grades: Grade[];
  /** The alert threshold (two decimals): a person whose due-total rises from at or below it to above it is alerted;
   *  null when it is off. Not dated. */
  alertThreshold: string | null;
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
export const PERM_ACTIONS = ['open', 'settle', 'void', 'reassign', 'close', 'reopen', 'today'] as const;
export type PermAction = (typeof PERM_ACTIONS)[number];

/** The roles that may take each action while its matrix entry is blank. */
export const STANDING_PERMISSIONS: Record<PermAction, string> = {
  open: 'clerk,steward', settle: 'clerk,steward', void: 'clerk,steward',
  reassign: 'steward', close: 'clerk,steward', reopen: 'steward', today: 'clerk,steward,restricted',
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

/** The policy version in force on a date (yyyy-mm-dd): how many versions have a from-date on or before it, one with
 *  none counting always. */
export function policyVersionOn(versions: readonly PolicyVersion[], date: string): number {
  return versions.filter((v) => v.from === null || v.from <= date).length;
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
  /** The date (yyyy-mm-dd) this screen previews; null: none. Belongs to the signed-in user's screen, so loading a
   *  sheet leaves it as it is; while set, every figure on screen is worked out over the sheet's current records as if
   *  it were the sheet's today, and every read carries it in the X-Preview header. Changes still use the real today. */
  private readonly previewDate = signal<string | null>(null);
  readonly preview = this.previewDate.asReadonly();
  /** The active sheet as the screen works its figures out: while a preview date is set, it stands in for its today. */
  readonly current = computed(() => {
    const sheet = this.active();
    const preview = this.previewDate();
    return sheet && preview ? { ...sheet, today: preview } : sheet;
  });
  /** The active sheet's grades: every grade select, the summary and the rates list these. */
  readonly grades = computed<readonly Grade[]>(() => this.current()?.grades ?? GRADES);
  /** The active sheet's real today (yyyy-mm-dd), whatever the preview: every change and its defaults use it. */
  readonly today = computed(() => this.active()?.today ?? '');
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

  /** Preview a date (null: none), re-loading the sheet so everything on screen is worked out on it. */
  setPreview(id: string, preview: string | null): Observable<SheetView> {
    this.previewDate.set(preview);
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

  /** Clone the active sheet to another id, never used before (a steward only); answers with the clone. */
  cloneTo(id: string, to: string): Observable<SheetView> {
    return this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/clone`, { to });
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

  /** Change the active sheet's escalation gap in days; null means off. Not dated. */
  setEscalateAfter(id: string, escalateAfter: number | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/escalate-after`, { escalateAfter })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's review SLA in days; null means none. Not dated. */
  setReviewSla(id: string, reviewSla: number | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/review-sla`, { reviewSla })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's pending SLA in days; null means none. Not dated. */
  setPendingSla(id: string, pendingSla: number | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/pending-sla`, { pendingSla })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's maximum of open claims per person; null means none. Not dated. */
  setMaxOpen(id: string, maxOpen: number | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/max-open`, { maxOpen })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's mid score threshold, a whole number; null means none. Not dated. */
  setScoreMid(id: string, value: number | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/score-mid`, { value })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's high score threshold, a whole number; null means none. Not dated. */
  setScoreHigh(id: string, value: number | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/score-high`, { value })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Turn the active sheet's score log switch on ('on') or off (null). Not dated. */
  setScoreLog(id: string, value: 'on' | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/score-log`, { value })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's fiscal year start month, 1 to 12. Not dated. */
  setFiscalStartMonth(id: string, month: number): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/fiscal-start-month`, { month })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's pot; null means none. Not dated. */
  setPot(id: string, pot: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/pot`, { pot })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's proof kinds (comma separated); null means none. Not dated. */
  setProofKinds(id: string, proofKinds: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/proof-kinds`, { proofKinds })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's default grade; null means none (then A). Not dated. */
  setDefaultGrade(id: string, defaultGrade: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/default-grade`, { defaultGrade })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Add a grade to the active sheet: a code of one to three capital letters not yet a grade; refused otherwise. */
  addGrade(id: string, grade: string): Observable<SheetView> {
    return this.http
      .post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/grades`, { grade })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Retire a grade on the active sheet; refused for one already retired. */
  retireGrade(id: string, grade: string): Observable<SheetView> {
    return this.http
      .post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/grades/${grade}/retire`, null)
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's alert threshold; null means off. Not dated. */
  setAlertThreshold(id: string, alertThreshold: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/alert-threshold`, { alertThreshold })
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

  /** Record a grade's own clerk limit from a date (null: the sheet's today); null means the sheet's. */
  setGradeClerkLimit(id: string, grade: Grade, clerkLimit: string | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/clerk-limit/${grade}`, { clerkLimit, from })
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

  /** Record the active sheet's review threshold from a date (null: its today); null means none. */
  setReviewThreshold(id: string, reviewThreshold: string | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/review-threshold`, { reviewThreshold, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record the active sheet's rounding mode (half-up or half-even) from a date (null: its today); null means none. */
  setRounding(id: string, rounding: string | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/rounding`, { rounding, from })
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

// While the screen previews a date, every API call carries it in the X-Preview header; only reads heed it.
export const previewHeader: HttpInterceptorFn = (req, next) => {
  const preview = inject(Sheets).preview();
  return next(preview ? req.clone({ setHeaders: { 'X-Preview': preview } }) : req);
};
