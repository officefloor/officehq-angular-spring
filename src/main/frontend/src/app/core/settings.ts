import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** The roles a user name can have on a sheet; a name with none set is a clerk. */
export const ROLES = ['clerk', 'steward'] as const;
export type RoleName = (typeof ROLES)[number];

export interface RoleView {
  user: string;
  role: RoleName;
}

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
}

/** A clerk limit as the form takes it: up to two decimals, or blank for no limit. */
export const LIMIT = /^(\d{1,12}(\.\d{1,2})?)?$/;

/** A floor as the form takes it: 0.00 or more, with up to two decimals. */
export const FLOOR = /^\d{1,12}(\.\d{1,2})?$/;

/** A late percent as the form takes it: 0.00 or more, with up to two decimals. */
export const LATE_PCT = /^\d{1,3}(\.\d{1,2})?$/;

/** A policy date as the form takes it: yyyy-mm-dd, or blank for the sheet's today. */
export const POLICY_DATE = /^(\d{4}-\d{2}-\d{2})?$/;

/** Terms as the form takes them: a whole number of days. */
export const TERMS = /^\d{1,4}$/;

// The sheet's settings and the roles its user names have, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);

  get(sheet: string): Promise<SettingsView> {
    return firstValueFrom(this.http.get<SettingsView>(`${this.url(sheet)}/settings`));
  }

  /** Set the clerk limit; a blank value means no limit. */
  setClerkLimit(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/clerk-limit`, { value }));
  }

  /** Set the floor. */
  setFloor(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/floor`, { value }));
  }

  /** Set the terms, in days. */
  setTerms(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/terms`, { value }));
  }

  /** Set the late percent. */
  setLatePct(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/late-pct`, { value }));
  }

  /** Set the policy date; a blank value means the sheet's today. */
  setPolicyDate(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/policy-date`, { value }));
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
