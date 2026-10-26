import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { readAsOf } from '../../core/as-of.interceptor';
import { Grade } from '../people/people-api';
import { DatedValue } from './dated';

/** The terms of a sheet that has not set them, in days. */
export const DEFAULT_TERMS = 14;

/** The roles a sheet can assign a user name; any name not assigned one is a clerk. */
export const ROLES = ['clerk', 'steward', 'viewer', 'restricted'] as const;
export type Role = (typeof ROLES)[number];

/** The actions the sheet's permission matrix names, each set under perm-<action>. */
export const PERMISSION_ACTIONS = ['open', 'settle', 'void', 'reassign', 'close', 'reopen'] as const;
export type PermissionAction = (typeof PERMISSION_ACTIONS)[number];

export interface RoleView {
  user: string;
  role: Role;
}

/** A steward's delegation of the steward role to a user, from a date to a date (yyyy-mm-dd), inclusive. */
export interface DelegationView {
  id: number;
  user: string;
  from: string;
  to: string;
  /** The steward who delegated. */
  by: string;
}

export interface SettingsView {
  /** The highest due a clerk may settle, with two decimals, or null when there is no limit. */
  clerkLimit: string | null;
  /** The least a claim's live due can be, with two decimals. */
  floor: string;
  /** The days after a claim's date it is due by. */
  terms: number;
  /** What an overdue claim gains of its figure for every full 30 days overdue, with two decimals. */
  latePct: string;
  /** The policy date (yyyy-mm-dd) a claim dated before rounds half up, and on or after half to even;
   *  null when blank, the sheet's today then. */
  policyDate: string | null;
  /** The roles the sheet has assigned, by user name. */
  roles: RoleView[];
  /** The date (yyyy-mm-dd) from which, once the sheet's today reaches it, a claim opened is numbered
   *  within its period; null when blank (sheet-wide numbering). */
  periodNumberingFrom: string | null;
  /** The terms each grade set for itself, in days, by grade; a grade not here uses the sheet's terms. */
  gradeTerms: Partial<Record<Grade, number>>;
  /** The most a person's due-total can be, with two decimals, or null when there is no cap. */
  cap: string | null;
  /** The due above which a claim needs a proof to be settled or approved, with two decimals, or null when there is none. */
  proofThreshold: string | null;
  /** The due above which a claim needs a note to be opened, with two decimals, or null when there is none. */
  noteThreshold: string | null;
  /** By key, the values the dated settings (floor, late-pct, terms, terms-<grade>, grace-<grade>, clerk-limit, clerk-limit-<grade>, cap, proof-threshold, proof-threshold-<grade>,
   *  note-threshold) take
   *  from their from-dates, in date order. */
  dated: Partial<Record<string, DatedValue[]>>;
  /** By action, the roles that may take it as the sheet set them, comma separated; blank keeps the standing rule. */
  permissions: Partial<Record<PermissionAction, string>>;
  /** The days after an open period's to-date before a change of the sheet's today closes it, or null when blank (off). */
  graceDays: number | null;
  /** The stewards' delegations of their role, in the order they were made. */
  delegations: DelegationView[];
  /** The date (yyyy-mm-dd) a new claim given no date takes instead of the sheet's today, or null when
   *  blank (off). */
  entryDate: string | null;
  /** The amount a balanced pool shares out among its members, with two decimals, or null when there is
   *  none. Not dated. */
  pot: string | null;
  /** The sheet whose rates (then its parent's, up to three levels up) are used where this sheet has none
   *  in force, or null when it has none. Not dated. */
  parent: string | null;
  /** The month, 1 to 12, each fiscal year starts on; 1 unless set. Not dated. */
  fiscalStartMonth: number;
  /** The amount a person's due-total going above audits an alert, with two decimals, or null when off.
   *  Not dated. */
  alertThreshold: string | null;
  /** The days since a claim's previous reminder after which reminding it escalates it, or null when off.
   *  Not dated. */
  escalateAfter: number | null;
  /** The days a REVIEW claim may wait from its review day before it is review-late, or null when none.
   *  Not dated. */
  reviewSla: number | null;
  /** The days a PENDING claim may wait from its pending day before it is pending-late, or null when none.
   *  Not dated. */
  pendingSla: number | null;
  /** The most OPEN or PENDING claims a person may have before opening another is refused, or null when
   *  none. Not dated. */
  maxOpen: number | null;
  /** The proof kinds a claim above the proof threshold needs a proof of each of, as a comma separated
   *  list, or null when none. Not dated. */
  proofKinds: string | null;
  /** The grade a new person given none takes (quick add, an import line with a blank grade), or null when
   *  blank (A then). Not dated. */
  defaultGrade: string | null;
  /** The mid score threshold, a whole number, or null when blank. Not dated. */
  scoreMid: number | null;
  /** The high score threshold, a whole number, or null when blank. Not dated. */
  scoreHigh: number | null;
  /** The score log switch: 'on', or null when off. Not dated. */
  scoreLog: 'on' | null;
}

// The sheet's settings and the roles it assigns user names, as the store holds them.
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  /** The sheet's settings; as they stood at the end of the as-of date when one is given. */
  get(sheetId: string, asOf?: string): Promise<SettingsView> {
    return firstValueFrom(this.http.get<SettingsView>(`${this.url(sheetId)}/settings`, readAsOf(asOf)));
  }

  /** Record the clerk limit from a date (null: the sheet's today); null clears it from then, leaving no limit. */
  setClerkLimit(sheetId: string, value: string | null, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/clerk-limit`, { value, from }));
  }

  /** Record the cap from a date (null: the sheet's today); null clears it from then, leaving no cap. */
  setCap(sheetId: string, value: string | null, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/cap`, { value, from }));
  }

  /** Record the proof threshold from a date (null: the sheet's today); null clears it from then, leaving none. */
  setProofThreshold(sheetId: string, value: string | null, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/proof-threshold`, { value, from }));
  }

  /** Record a grade's own clerk limit from a date (null: the sheet's today); null clears it from then, leaving the sheet's. */
  setGradeClerkLimit(sheetId: string, grade: Grade, value: string | null, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/clerk-limit/${grade}`, { value, from }));
  }

  /** Record a grade's own proof threshold from a date (null: the sheet's today); null clears it from then, leaving the sheet's. */
  setGradeProofThreshold(sheetId: string, grade: Grade, value: string | null, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/proof-threshold/${grade}`, { value, from }));
  }

  /** Record the note threshold from a date (null: the sheet's today); null clears it from then, leaving none. */
  setNoteThreshold(sheetId: string, value: string | null, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/note-threshold`, { value, from }));
  }

  /** Record the review threshold from a date (null: the sheet's today); null clears it from then, leaving none. */
  setReviewThreshold(sheetId: string, value: string | null, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/review-threshold`, { value, from }));
  }

  /** Record the floor from a date (null: the sheet's today). */
  setFloor(sheetId: string, value: string, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/floor`, { value, from }));
  }

  /** Record the rounding mode (half-up or half-even) from a date (null: the sheet's today); blank clears it from then. */
  setRounding(sheetId: string, value: string, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/rounding`, { value, from }));
  }

  /** Record the terms, in days, from a date (null: the sheet's today). */
  setTerms(sheetId: string, value: number, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/terms`, { value, from }));
  }

  /** Record a grade's own terms, in days, from a date (null: the sheet's today); null clears them from then, leaving the sheet's terms. */
  setGradeTerms(sheetId: string, grade: Grade, value: number | null, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/terms/${grade}`, { value, from }));
  }

  /** Record a grade's grace days from a date (null: the sheet's today); null clears them from then. */
  setGradeGrace(sheetId: string, grade: Grade, value: number | null, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/grace/${grade}`, { value, from }));
  }

  /** Record the late percent from a date (null: the sheet's today). */
  setLatePct(sheetId: string, value: string, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/late-pct`, { value, from }));
  }

  /** Set the policy date; null clears it, leaving the sheet's today. */
  setPolicyDate(sheetId: string, value: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/policy-date`, { value }));
  }

  /** Set the date claims are numbered within their periods from; null clears it, leaving sheet-wide numbering. */
  setPeriodNumberingFrom(sheetId: string, value: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/period-numbering-from`, { value }));
  }

  /** Set the entry date; null clears it, so a new claim given no date takes the sheet's today. */
  setEntryDate(sheetId: string, value: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/entry-date`, { value }));
  }

  /** Set the month each fiscal year starts on, 1 to 12. */
  setFiscalStartMonth(sheetId: string, value: number): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/fiscal-start-month`, { value }));
  }

  /** Set the proof kinds as a comma separated list; null clears them. */
  setProofKinds(sheetId: string, value: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/proof-kinds`, { value }));
  }

  /** Set the default grade; null clears it, leaving A. */
  setDefaultGrade(sheetId: string, value: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/default-grade`, { value }));
  }

  /** Set the alert threshold; null clears it, turning alerts off. */
  setAlertThreshold(sheetId: string, value: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/alert-threshold`, { value }));
  }

  /** Set the pot; null clears it, leaving no pot. */
  setPot(sheetId: string, value: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/pot`, { value }));
  }

  /** Set the parent sheet; null clears it, leaving no parent. */
  setParent(sheetId: string, value: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/parent`, { value }));
  }

  /** Set the review SLA in days; null clears it, leaving none. */
  setReviewSla(sheetId: string, value: number | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/review-sla`, { value }));
  }

  setPendingSla(sheetId: string, value: number | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/pending-sla`, { value }));
  }

  /** Set the maximum of open claims per person; null clears it, leaving none. */
  setMaxOpen(sheetId: string, value: number | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/max-open`, { value }));
  }

  /** Set the mid score threshold; null clears it. */
  setScoreMid(sheetId: string, value: number | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/score-mid`, { value }));
  }

  /** Set the high score threshold; null clears it. */
  setScoreHigh(sheetId: string, value: number | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/score-high`, { value }));
  }

  /** Turn the score log on ('on'), or off (null). */
  setScoreLog(sheetId: string, value: 'on' | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/score-log`, { value }));
  }

  /** Set the escalation gap in days; null clears it, turning escalation off. */
  setEscalateAfter(sheetId: string, value: number | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/escalate-after`, { value }));
  }

  /** Set the grace days; null clears them, turning the closing of periods off. */
  setGraceDays(sheetId: string, value: number | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/grace-days`, { value }));
  }

  /** Set the roles that may take an action, comma separated; blank keeps the standing rule. */
  setPermission(sheetId: string, action: PermissionAction, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/perm/${action}`, { value }));
  }

  /** Assign a user name a role on the sheet. */
  setRole(sheetId: string, user: string, role: Role): Promise<RoleView> {
    return firstValueFrom(this.http.put<RoleView>(`${this.url(sheetId)}/roles`, { user, role }));
  }

  /** Delegate the steward role to a user from a date (null: the sheet's today) to a date, inclusive;
   *  refused to all but a steward, and when the to-date is before the from-date. */
  delegate(sheetId: string, user: string, from: string | null, to: string): Promise<DelegationView> {
    return firstValueFrom(this.http.post<DelegationView>(`${this.url(sheetId)}/delegations`, { user, from, to }));
  }
}
