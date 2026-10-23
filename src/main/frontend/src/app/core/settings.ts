import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { DatedValue } from './session';

/** The roles a user name can have on a sheet; a name with none set is a clerk, and a viewer can change nothing. */
export const ROLES = ['clerk', 'steward', 'viewer'] as const;
export type RoleName = (typeof ROLES)[number];

export interface RoleView {
  user: string;
  role: RoleName;
}

/** The settings that carry a figure: each is saved from a from-date and shown as in force on the sheet's today. */
export type DatedKey = 'clerk-limit' | 'cap' | 'floor' | 'terms' | 'terms-A' | 'terms-B' | 'terms-C' | 'grace-A' | 'grace-B' | 'grace-C' | 'late-pct' | 'proof-threshold' | 'proof-threshold-A' | 'proof-threshold-B' | 'proof-threshold-C' | 'note-threshold' | 'review-threshold' | 'rounding';

/** A from-date as a dated setting takes it: yyyy-mm-dd, or blank for the sheet's today. */
export const SETTING_FROM = /^(\d{4}-\d{2}-\d{2})?$/;

/** The settings as in force on the sheet's today. */
export interface SettingsView {
  /** The clerk limit with two decimals, e.g. "1.50"; null when there is no limit. */
  clerkLimit: string | null;
  /** The floor with two decimals, e.g. "1.00": a claim's live due is at least it. */
  floor: string;
  /** The terms in days: a claim is due by its date plus them. */
  terms: number;
  /** The late percent with two decimals, e.g. "10.00": an overdue claim gains it of its figure every full 30 days. */
  latePct: string;
  /** The policy date (yyyy-mm-dd); null when blank (the sheet's today is then the policy date). */
  policyDate: string | null;
  /** Period numbering from (yyyy-mm-dd): claims opened on or after it are numbered within their period; null when off. */
  periodNumberingFrom: string | null;
  /** Entry date (yyyy-mm-dd): while set, a new claim with no date is dated it rather than the sheet's today; null when off. */
  entryDate: string | null;
  /** Grace days: an open period closes itself once the sheet's today is past its to-date plus them; null when off. */
  graceDays: number | null;
  /** Each grade's own terms in days; null when the grade uses the sheet's terms. */
  termsA: number | null;
  termsB: number | null;
  termsC: number | null;
  /** The cap with two decimals, e.g. "8.00": a person's due-total is at most it; null when there is none. */
  cap: string | null;
  /** The floor's values by from-date, its starting value first. */
  floorHistory: DatedValue[];
  /** The late percent's values by from-date, its starting value first. */
  latePctHistory: DatedValue[];
  /** The proof threshold with two decimals: a claim whose due is above it needs a proof to be settled; null when there is none. */
  proofThreshold: string | null;
  /** The note threshold with two decimals: a claim whose due is above it needs a note to be opened; null when there is none. */
  noteThreshold: string | null;
  /** The review threshold with two decimals: a claim whose due is above it goes to REVIEW when opened; null when there is none. */
  reviewThreshold: string | null;
  /** Each grade's grace in days: its claims are overdue, and late, only from their due-by plus them; null when none. */
  graceA: number | null;
  graceB: number | null;
  graceC: number | null;
  /** Each grade's grace values by from-date (a value null when none), its starting value (none) first. */
  graceHistory: Record<string, DatedValue[]>;
  /** Each grade's proof threshold with two decimals: its claims use it in place of the sheet's; null when none. */
  gradeProofThresholds: Record<Grade, string | null>;
  /** The pot with two decimals: split among each balanced pool's members as their cuts; null when there is none. */
  pot: string | null;
  /** The parent sheet's id: its rates apply where this sheet has none in force; null when there is none. */
  parent: string | null;
  /** The month, 1 to 12, the fiscal year starts in; 1 unless set. */
  fiscalStartMonth: number;
  /** The alert threshold with two decimals: a person whose due-total crosses above it is alerted; null when off. */
  alertThreshold: string | null;
  /** The rounding mode in force (half-up or half-even): claims dated while it is in force round by it; null when none. */
  rounding: string | null;
  /** The rounding mode's values by from-date (a value null when none), its starting value (none) first. */
  roundingHistory: DatedValue[];
  /** For each action of the permission matrix, the comma separated roles that may take it; blank for its standing rule. */
  permissions: Record<PermissionAction, string>;
}

/** The actions the permission matrix covers. */
export const PERMISSION_ACTIONS = ['open', 'settle', 'void', 'reassign', 'close', 'reopen'] as const;
export type PermissionAction = (typeof PERMISSION_ACTIONS)[number];

/** An action's roles as the form takes them: clerk or steward, comma separated, or blank for the standing rule. */
export const PERMISSION = /^((clerk|steward)\s*(,\s*(clerk|steward)\s*)*)?$/;

/** The grades that can have their own terms. */
export const GRADES = ['A', 'B', 'C'] as const;
export type Grade = (typeof GRADES)[number];

/** A grade's terms as the form takes them: a whole number of days, or blank for the sheet's terms. */
export const GRADE_TERMS = /^(\d{1,4})?$/;

/** A grade's grace as the form takes it: a whole number of days, or blank for none. */
export const GRADE_GRACE = /^(\d{1,4})?$/;

/** A clerk limit as the form takes it: up to two decimals, or blank for no limit. */
export const LIMIT = /^(\d{1,12}(\.\d{1,2})?)?$/;

/** A cap as the form takes it: up to two decimals, or blank for no cap. */
export const CAP = /^(\d{1,12}(\.\d{1,2})?)?$/;

/** A proof threshold as the form takes it: up to two decimals, or blank for none. */
export const PROOF_THRESHOLD = /^(\d{1,12}(\.\d{1,2})?)?$/;

/** A note threshold as the form takes it: up to two decimals, or blank for none. */
export const NOTE_THRESHOLD = /^(\d{1,12}(\.\d{1,2})?)?$/;

/** A rounding mode as the form takes it: half-up or half-even, or blank for none. */
export const ROUNDING = /^(half-up|half-even)?$/;

/** A floor as the form takes it: 0.00 or more, with up to two decimals. */
export const FLOOR = /^\d{1,12}(\.\d{1,2})?$/;

/** A late percent as the form takes it: 0.00 or more, with up to two decimals. */
export const LATE_PCT = /^\d{1,3}(\.\d{1,2})?$/;

/** A policy date as the form takes it: yyyy-mm-dd, or blank for the sheet's today. */
export const POLICY_DATE = /^(\d{4}-\d{2}-\d{2})?$/;

/** A period-numbering-from date as the form takes it: yyyy-mm-dd, or blank for off. */
export const PERIOD_NUMBERING_FROM = /^(\d{4}-\d{2}-\d{2})?$/;

/** An entry date as the form takes it: yyyy-mm-dd, or blank for off. */
export const ENTRY_DATE = /^(\d{4}-\d{2}-\d{2})?$/;

/** Grace days as the form takes them: a whole number of days, or blank for off. */
export const GRACE_DAYS = /^(\d{1,4})?$/;

/** A pot as the form takes it: up to two decimals, or blank for none. */
export const POT = /^(\d{1,12}(\.\d{1,2})?)?$/;

/** An alert threshold as the form takes it: up to two decimals, or blank for off. */
export const ALERT_THRESHOLD = /^(\d{1,12}(\.\d{1,2})?)?$/;

/** A parent sheet id as the form takes it, or blank for none. */
export const PARENT = /^[A-Za-z0-9._-]{0,64}$/;

/** A fiscal start month as the form takes it: a month number, 1 to 12. */
export const FISCAL_START_MONTH = /^([1-9]|1[0-2])$/;

/** Terms as the form takes them: a whole number of days. */
export const TERMS = /^\d{1,4}$/;

// The sheet's settings and the roles its user names have, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);

  get(sheet: string): Promise<SettingsView> {
    return firstValueFrom(this.http.get<SettingsView>(`${this.url(sheet)}/settings`));
  }

  /** Set the clerk limit from a date (blank: the sheet's today); a blank value means no limit. */
  setClerkLimit(sheet: string, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/clerk-limit`, { value, from }));
  }

  /** Set the cap from a date (blank: the sheet's today); a blank value means no cap. */
  setCap(sheet: string, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/cap`, { value, from }));
  }

  /** Set a grade's proof threshold from a date (blank: the sheet's today); a blank value means none (the sheet's applies). */
  setGradeProofThreshold(sheet: string, grade: Grade, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/proof-threshold-${grade}`, { value, from }));
  }

  /** Set the proof threshold from a date (blank: the sheet's today); a blank value means none. */
  setProofThreshold(sheet: string, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/proof-threshold`, { value, from }));
  }

  /** Set the review threshold from a date (blank: the sheet's today); a blank value means none. */
  setReviewThreshold(sheet: string, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/review-threshold`, { value, from }));
  }

  /** Set the note threshold from a date (blank: the sheet's today); a blank value means none. */
  setNoteThreshold(sheet: string, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/note-threshold`, { value, from }));
  }

  /** Set the floor from a date (blank: the sheet's today). */
  setFloor(sheet: string, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/floor`, { value, from }));
  }

  /** Set the rounding mode from a date (blank: the sheet's today); a blank value means none. */
  setRounding(sheet: string, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/rounding`, { value, from }));
  }

  /** Set the terms, in days, from a date (blank: the sheet's today). */
  setTerms(sheet: string, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/terms`, { value, from }));
  }

  /** Set a grade's own terms, in days, from a date (blank: the sheet's today); a blank value means the grade uses the sheet's terms. */
  setGradeTerms(sheet: string, grade: Grade, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/terms-${grade}`, { value, from }));
  }

  /** Set a grade's grace, in days, from a date (blank: the sheet's today); a blank value means none. */
  setGradeGrace(sheet: string, grade: Grade, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/grace-${grade}`, { value, from }));
  }

  /** Set the late percent from a date (blank: the sheet's today). */
  setLatePct(sheet: string, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/late-pct`, { value, from }));
  }

  /** Set the policy date; a blank value means the sheet's today. */
  setPolicyDate(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/policy-date`, { value }));
  }

  /** Set the entry date; a blank value turns it off. */
  setEntryDate(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/entry-date`, { value }));
  }

  /** Set the period-numbering-from date; a blank value turns period numbering off. */
  setPeriodNumberingFrom(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/period-numbering-from`, { value }));
  }

  /** Set the pot; a blank value means none. */
  setPot(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/pot`, { value }));
  }

  /** Set the alert threshold; a blank value means off. */
  setAlertThreshold(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/alert-threshold`, { value }));
  }

  /** Set the parent sheet; a blank value means none. */
  setParent(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/parent`, { value }));
  }

  /** Set the month, 1 to 12, the fiscal year starts in. */
  setFiscalStartMonth(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/fiscal-start-month`, { value }));
  }

  /** Set the grace days; a blank value turns closing periods by themselves off. */
  setGraceDays(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/grace-days`, { value }));
  }

  /** Set the roles that may take an action, comma separated; a blank value keeps its standing rule. */
  setPermission(sheet: string, action: PermissionAction, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/perm-${action}`, { value }));
  }

  /** The roles set on the sheet, by user name. */
  roles(sheet: string): Promise<RoleView[]> {
    return firstValueFrom(this.http.get<RoleView[]>(`${this.url(sheet)}/roles`));
  }

  /** Set a user name's role on the sheet. */
  setRole(sheet: string, form: RoleView): Promise<RoleView> {
    return firstValueFrom(this.http.put<RoleView>(`${this.url(sheet)}/roles`, form));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
