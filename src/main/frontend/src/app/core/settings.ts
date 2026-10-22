import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { DatedValue } from './session';

/** The roles a user name can have on a sheet; a name with none set is a clerk. */
export const ROLES = ['clerk', 'steward'] as const;
export type RoleName = (typeof ROLES)[number];

export interface RoleView {
  user: string;
  role: RoleName;
}

/** The settings that carry a figure: each is saved from a from-date and shown as in force on the sheet's today. */
export type DatedKey = 'clerk-limit' | 'cap' | 'floor' | 'terms' | 'terms-A' | 'terms-B' | 'terms-C' | 'late-pct';

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
}

/** The grades that can have their own terms. */
export const GRADES = ['A', 'B', 'C'] as const;
export type Grade = (typeof GRADES)[number];

/** A grade's terms as the form takes them: a whole number of days, or blank for the sheet's terms. */
export const GRADE_TERMS = /^(\d{1,4})?$/;

/** A clerk limit as the form takes it: up to two decimals, or blank for no limit. */
export const LIMIT = /^(\d{1,12}(\.\d{1,2})?)?$/;

/** A cap as the form takes it: up to two decimals, or blank for no cap. */
export const CAP = /^(\d{1,12}(\.\d{1,2})?)?$/;

/** A floor as the form takes it: 0.00 or more, with up to two decimals. */
export const FLOOR = /^\d{1,12}(\.\d{1,2})?$/;

/** A late percent as the form takes it: 0.00 or more, with up to two decimals. */
export const LATE_PCT = /^\d{1,3}(\.\d{1,2})?$/;

/** A policy date as the form takes it: yyyy-mm-dd, or blank for the sheet's today. */
export const POLICY_DATE = /^(\d{4}-\d{2}-\d{2})?$/;

/** A period-numbering-from date as the form takes it: yyyy-mm-dd, or blank for off. */
export const PERIOD_NUMBERING_FROM = /^(\d{4}-\d{2}-\d{2})?$/;

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

  /** Set the floor from a date (blank: the sheet's today). */
  setFloor(sheet: string, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/floor`, { value, from }));
  }

  /** Set the terms, in days, from a date (blank: the sheet's today). */
  setTerms(sheet: string, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/terms`, { value, from }));
  }

  /** Set a grade's own terms, in days, from a date (blank: the sheet's today); a blank value means the grade uses the sheet's terms. */
  setGradeTerms(sheet: string, grade: Grade, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/terms-${grade}`, { value, from }));
  }

  /** Set the late percent from a date (blank: the sheet's today). */
  setLatePct(sheet: string, value: string, from: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/late-pct`, { value, from }));
  }

  /** Set the policy date; a blank value means the sheet's today. */
  setPolicyDate(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/policy-date`, { value }));
  }

  /** Set the period-numbering-from date; a blank value turns period numbering off. */
  setPeriodNumberingFrom(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/period-numbering-from`, { value }));
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
