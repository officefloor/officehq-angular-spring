import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Grade } from '../people/people-api';
import { DatedValue } from './dated';

/** The terms of a sheet that has not set them, in days. */
export const DEFAULT_TERMS = 14;

/** The roles a sheet can assign a user name; any name not assigned one is a clerk. */
export const ROLES = ['clerk', 'steward'] as const;
export type Role = (typeof ROLES)[number];

export interface RoleView {
  user: string;
  role: Role;
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
  /** By key, the values the dated settings (floor, late-pct, terms, terms-<grade>, clerk-limit, cap) take
   *  from their from-dates, in date order. */
  dated: Partial<Record<string, DatedValue[]>>;
}

// The sheet's settings and the roles it assigns user names, as the store holds them.
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  get(sheetId: string): Promise<SettingsView> {
    return firstValueFrom(this.http.get<SettingsView>(`${this.url(sheetId)}/settings`));
  }

  /** Record the clerk limit from a date (null: the sheet's today); null clears it from then, leaving no limit. */
  setClerkLimit(sheetId: string, value: string | null, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/clerk-limit`, { value, from }));
  }

  /** Record the cap from a date (null: the sheet's today); null clears it from then, leaving no cap. */
  setCap(sheetId: string, value: string | null, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/cap`, { value, from }));
  }

  /** Record the floor from a date (null: the sheet's today). */
  setFloor(sheetId: string, value: string, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/floor`, { value, from }));
  }

  /** Record the terms, in days, from a date (null: the sheet's today). */
  setTerms(sheetId: string, value: number, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/terms`, { value, from }));
  }

  /** Record a grade's own terms, in days, from a date (null: the sheet's today); null clears them from then, leaving the sheet's terms. */
  setGradeTerms(sheetId: string, grade: Grade, value: number | null, from: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/terms/${grade}`, { value, from }));
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

  /** Assign a user name a role on the sheet. */
  setRole(sheetId: string, user: string, role: Role): Promise<RoleView> {
    return firstValueFrom(this.http.put<RoleView>(`${this.url(sheetId)}/roles`, { user, role }));
  }
}
