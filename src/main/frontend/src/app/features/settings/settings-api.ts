import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Grade } from '../people/people-api';

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

  /** Set the clerk limit; null clears it, leaving no limit. */
  setClerkLimit(sheetId: string, value: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/clerk-limit`, { value }));
  }

  /** Set the floor. */
  setFloor(sheetId: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/floor`, { value }));
  }

  /** Set the terms, in days. */
  setTerms(sheetId: string, value: number): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/terms`, { value }));
  }

  /** Set a grade's own terms, in days; null clears them, leaving the sheet's terms. */
  setGradeTerms(sheetId: string, grade: Grade, value: number | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/terms/${grade}`, { value }));
  }

  /** Set the late percent. */
  setLatePct(sheetId: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/late-pct`, { value }));
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
