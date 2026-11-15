import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { DatedSettings, Session, otherError } from '../../core/session';

/** The roles a user name can hold on a sheet; a name without one is a clerk. A viewer sees the
 *  sheet and may change nothing on it. A restricted user acts as a clerk, but its people list shows only the
 *  people of the crews its person leads or belongs to, and that person. */
export const ROLES = ['clerk', 'steward', 'viewer', 'restricted'] as const;

/** The actions whose roles the permission matrix sets, each with its standing rule (used while
 *  its entry is blank). */
export const PERMISSIONS = [
  { action: 'open', standing: 'clerk,steward' },
  { action: 'settle', standing: 'clerk,steward' },
  { action: 'void', standing: 'clerk,steward' },
  { action: 'reassign', standing: 'steward' },
  { action: 'close', standing: 'clerk,steward' },
  { action: 'reopen', standing: 'steward' },
] as const;

export type PermissionAction = (typeof PERMISSIONS)[number]['action'];

/** Roles that may take an action: clerk and/or steward, comma separated. */
export const PERMISSION_PATTERN = /^\s*(clerk|steward)\s*(,\s*(clerk|steward)\s*)*$/;

/** A limit of 0.00 or more with up to two decimals. */
export const LIMIT_PATTERN = /^\d{1,13}(\.\d{1,2})?$/;

/** A late percent of 0.00 or more with up to two decimals. */
export const LATE_PCT_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

/** A policy date as yyyy-mm-dd. */
export const POLICY_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** A rounding mode: half-up or half-even. */
export const ROUNDING_PATTERN = /^(half-up|half-even)$/;

/** Terms of a whole number of days. */
export const TERMS_PATTERN = /^\d{1,4}$/;
/** A score threshold: a whole number, possibly below zero. */
export const SCORE_PATTERN = /^-?\d{1,6}$/;
/** A fiscal year start month: 1 to 12. */
export const FISCAL_START_MONTH_PATTERN = /^(0?[1-9]|1[0-2])$/;

export interface Settings {
  /** Two decimals, as the server formats it; null when there is no limit. */
  clerkLimit: string | null;
  /** Two decimals, as the server formats it: a claim's live due is at least it. */
  floor: string;
  /** Days: a claim is due by its date plus them. */
  terms: number;
  /** Two decimals, as the server formats it: an overdue claim's due gains it for every full 30 days overdue. */
  latePct: string;
  /** yyyy-mm-dd: a claim dated before it rounds half up, one on or after it half to even; null when
   *  blank, when the sheet's today is the policy date. */
  policyDate: string | null;
  /** yyyy-mm-dd: while the sheet's today is on or after it, a claim settled is numbered within its
   *  period; null when blank (off). */
  periodNumberingFrom: string | null;
  /** yyyy-mm-dd: while set, a new claim left undated takes it instead of the sheet's today; null
   *  when blank (off). */
  entryDate: string | null;
  /** Days: when the sheet's today changes, every open period whose to-date plus them is before it is closed; null when blank (off). */
  graceDays: number | null;
  /** Days: reminding a claim whose previous reminder was at least this many days before the
   *  sheet's today escalates it; null when blank (off). Not dated. */
  escalateAfter: number | null;
  /** Days: a REVIEW claim whose review day plus them is before the sheet's today is late; null
   *  when blank (none). Not dated. */
  reviewSla: number | null;
  /** The most OPEN or PENDING claims a person may have: opening one more is refused; null when
   *  blank (none). Not dated. */
  maxOpen: number | null;
  /** A whole number: while both thresholds are set, a person whose score is at or above it shows
   *  MID (HIGH at or above the high one); null when blank. Not dated. */
  scoreMid: number | null;
  /** A whole number: while both thresholds are set, a person whose score is at or above it shows
   *  HIGH; null when blank. Not dated. */
  scoreHigh: number | null;
  /** Whether the score log is on: changing the sheet's today records each person's score on the
   *  day being left. Not dated. */
  scoreLog: boolean;
  /** Two decimals, as the server formats it: each balanced pool's members cut it by their shares;
   *  null when blank (none). Not dated. */
  pot: string | null;
  /** Two decimals, as the server formats it: a change that takes a person's due-total from at or
   *  below it to above it alerts; null when blank (off). Not dated. */
  alertThreshold: string | null;
  /** The month (1 to 12) the fiscal year starts in; 1 unless set. Not dated. */
  fiscalStartMonth: number;
  /** Days, by grade: the terms a grade has of its own; a grade not here uses the sheet's terms. */
  gradeTerms: Record<string, number>;
  /** Two decimals, as the server formats it: a person's due-total is at most it; null when there is none. */
  cap: string | null;
  /** Two decimals, as the server formats it: a claim whose due is above it cannot be settled or
   *  approved without a proof; null when there is none. */
  proofThreshold: string | null;
  /** Every value recorded for the dated settings, by key, each key's by from-date. */
  dated: DatedSettings;
  /** The roles set for each action, comma separated; an action left blank (its standing rule) is not here. */
  permissions: Partial<Record<PermissionAction, string>>;
}

export interface RoleGrant {
  user: string;
  role: string;
}

/** A steward's role delegated to a user name from a date to a date (yyyy-mm-dd), inclusive. */
export interface Delegation {
  id: number;
  user: string;
  steward: string;
  from: string;
  to: string;
}

// The sheet's settings and the roles user names hold on it, read and written through the server.
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  get(sheetId: string): Promise<Settings> {
    return firstValueFrom(this.http.get<Settings>(`${this.url(sheetId)}/settings`, { headers: this.headers() }));
  }

  /** Save the value from a date (blank: the sheet's today). */
  setClerkLimit(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/clerk-limit`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the grade's clerk limit (blank: none, the sheet's applies) from a date (blank: the sheet's today). */
  setGradeClerkLimit(sheetId: string, grade: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/clerk-limit/${encodeURIComponent(grade)}`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setFloor(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/floor`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the grade's grace days (blank: none) from a date (blank: the sheet's today). */
  setGradeGrace(sheetId: string, grade: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/grace/${encodeURIComponent(grade)}`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the grade's proof threshold (blank: none, the sheet's applies) from a date (blank: the sheet's today). */
  setGradeProofThreshold(sheetId: string, grade: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/proof-threshold/${encodeURIComponent(grade)}`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setTerms(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/terms`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the grade's own terms from a date (blank: the sheet's today). */
  setGradeTerms(sheetId: string, grade: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/terms/${encodeURIComponent(grade)}`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setLatePct(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/late-pct`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setCap(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/cap`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setProofThreshold(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/proof-threshold`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the rounding mode (half-up or half-even; blank: none) from a date (blank: the sheet's today). */
  setRounding(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/rounding`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setNoteThreshold(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/note-threshold`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setReviewThreshold(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/review-threshold`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the roles that may take an action (blank: its standing rule). */
  setPermission(sheetId: string, action: PermissionAction, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/perm/${action}`, { value }, { headers: this.headers() }),
    );
  }

  setPolicyDate(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/policy-date`, { value }, { headers: this.headers() }),
    );
  }

  setGraceDays(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/grace-days`, { value }, { headers: this.headers() }),
    );
  }

  setReviewSla(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/review-sla`, { value }, { headers: this.headers() }),
    );
  }

  setMaxOpen(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/max-open`, { value }, { headers: this.headers() }),
    );
  }

  setScoreMid(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/score-mid`, { value }, { headers: this.headers() }),
    );
  }

  setScoreHigh(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/score-high`, { value }, { headers: this.headers() }),
    );
  }

  setScoreLog(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/score-log`, { value }, { headers: this.headers() }),
    );
  }

  setEscalateAfter(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/escalate-after`, { value }, { headers: this.headers() }),
    );
  }

  setFiscalStartMonth(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/fiscal-start-month`, { value }, { headers: this.headers() }),
    );
  }

  setPot(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/pot`, { value }, { headers: this.headers() }),
    );
  }

  setAlertThreshold(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/alert-threshold`, { value }, { headers: this.headers() }),
    );
  }

  /** Set the sheet's proof kinds (receipt and/or photo, comma separated; blank: none); resolves to the kinds as set. */
  setProofKinds(sheetId: string, value: string): Promise<{ proofKinds: string | null }> {
    return firstValueFrom(
      this.http.put<{ proofKinds: string | null }>(`${this.url(sheetId)}/settings/proof-kinds`, { value }, { headers: this.headers() }),
    );
  }

  /** Set the sheet's default grade (one of the grades; blank: A); resolves to the grade as set. */
  setDefaultGrade(sheetId: string, value: string): Promise<{ defaultGrade: string | null }> {
    return firstValueFrom(
      this.http.put<{ defaultGrade: string | null }>(`${this.url(sheetId)}/settings/default-grade`, { value }, { headers: this.headers() }),
    );
  }

  /** Set the sheet's parent (blank: none); resolves to the parent as set. */
  setParent(sheetId: string, value: string): Promise<{ parent: string | null }> {
    return firstValueFrom(
      this.http.put<{ parent: string | null }>(`${this.url(sheetId)}/settings/parent`, { value }, { headers: this.headers() }),
    );
  }

  setEntryDate(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/entry-date`, { value }, { headers: this.headers() }),
    );
  }

  setPeriodNumberingFrom(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/period-numbering-from`, { value }, { headers: this.headers() }),
    );
  }

  roles(sheetId: string): Promise<RoleGrant[]> {
    return firstValueFrom(this.http.get<RoleGrant[]>(`${this.url(sheetId)}/roles`, { headers: this.headers() }));
  }

  setRole(sheetId: string, user: string, role: string): Promise<RoleGrant> {
    return firstValueFrom(this.http.put<RoleGrant>(`${this.url(sheetId)}/roles`, { user, role }, { headers: this.headers() }));
  }

  delegations(sheetId: string): Promise<Delegation[]> {
    return firstValueFrom(this.http.get<Delegation[]>(`${this.url(sheetId)}/delegations`, { headers: this.headers() }));
  }

  delegate(sheetId: string, user: string, from: string, to: string): Promise<Delegation> {
    return firstValueFrom(
      this.http.post<Delegation>(`${this.url(sheetId)}/delegations`, { user, from, to }, { headers: this.headers() }),
    );
  }

  /** The sheet's holidays (yyyy-mm-dd), earliest first. */
  holidays(sheetId: string): Promise<string[]> {
    return firstValueFrom(this.http.get<string[]>(`${this.url(sheetId)}/holidays`, { headers: this.headers() }));
  }

  addHoliday(sheetId: string, date: string): Promise<string> {
    return firstValueFrom(this.http.post<string>(`${this.url(sheetId)}/holidays`, { date }, { headers: this.headers() }));
  }

  /** The sheet's void reasons, in the order they were added. */
  voidReasons(sheetId: string): Promise<string[]> {
    return firstValueFrom(this.http.get<string[]>(`${this.url(sheetId)}/void-reasons`, { headers: this.headers() }));
  }

  /** Add a void reason; resolves to the sheet's void reasons with it. */
  addVoidReason(sheetId: string, reason: string): Promise<string[]> {
    return firstValueFrom(
      this.http.post<string[]>(`${this.url(sheetId)}/void-reasons`, { reason }, { headers: this.headers() }),
    );
  }

  /** Add a grade; resolves to the sheet's grades with it. */
  addGrade(sheetId: string, grade: string): Promise<{ grades: string[] }> {
    return firstValueFrom(
      this.http.post<{ grades: string[] }>(`${this.url(sheetId)}/grades`, { grade }, { headers: this.headers() }),
    );
  }

  /** Retire a grade; resolves to the sheet's retired grades with it. */
  retireGrade(sheetId: string, grade: string): Promise<{ retiredGrades: string[] }> {
    return firstValueFrom(
      this.http.post<{ retiredGrades: string[] }>(`${this.url(sheetId)}/grades/retire`, { grade }, { headers: this.headers() }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export const SETTINGS_INVALID =
  'Enter a clerk limit of 0.00 or more with up to two decimals (blank: no limit) and a clerk limit for each grade of 0.00 or more with up to two decimals (blank: the sheet\'s clerk limit) and a floor of 0.00 or more with up to two decimals (blank: 1.00) and terms of a whole number of business days (blank: 14) and terms for each grade of a whole number of business days (blank: the sheet\'s terms) and grace days for each grade of a whole number of days (blank: none) and a late percent of 0.00 or more with up to two decimals (blank: 0.00) and a policy date as yyyy-mm-dd (blank: the sheet\'s today) and a period numbering from-date as yyyy-mm-dd (blank: off) and an entry date as yyyy-mm-dd (blank: off) and a cap of 0.00 or more with up to two decimals (blank: none) and a proof threshold of 0.00 or more with up to two decimals (blank: none) and grace days of a whole number of days (blank: off) and a pot of 0.00 or more with up to two decimals (blank: none) and an alert threshold of 0.00 or more with up to two decimals (blank: off) and a fiscal start month from 1 to 12 and a parent sheet id without spaces (blank: none) and proof kinds as receipt and/or photo, comma separated (blank: none) and the roles for each action as clerk and/or steward, comma separated (blank: the standing rule), each from-date as yyyy-mm-dd (blank: the sheet\'s today).';

export function settingsError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return SETTINGS_INVALID;
  if (e instanceof HttpErrorResponse && e.status === 422) return 'The parent must be another sheet that has been loaded and does not have this sheet as an ancestor.';
  return otherError(e);
}

export function roleError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a user name without spaces and a role of clerk, steward, viewer or restricted.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The role was changed at the same moment; try again.';
  return otherError(e);
}

export function delegateError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a user name without spaces and a to-date on or after the from-date, each as yyyy-mm-dd.';
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward can delegate the steward role.';
  return otherError(e);
}

export function voidReasonError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a void reason.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That reason is on the list already.';
  return otherError(e);
}

export function holidayError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a holiday as yyyy-mm-dd.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That date is a holiday already.';
  return otherError(e);
}

export function addGradeError(e: unknown): string {
  if (e instanceof HttpErrorResponse && (e.status === 400 || e.status === 422)) return 'Enter a grade of one to three capital letters.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That grade is a grade already.';
  return otherError(e);
}

export function retireGradeError(e: unknown): string {
  if (e instanceof HttpErrorResponse && (e.status === 400 || e.status === 422)) return 'Choose one of the grades.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That grade is retired already.';
  return otherError(e);
}
