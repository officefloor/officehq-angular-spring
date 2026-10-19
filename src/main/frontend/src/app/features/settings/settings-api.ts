import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Session } from '../../core/session';
import { Dated } from './dated';

/** The roles a user name can have on a sheet; any name without one is a clerk. */
export const ROLES = ['clerk', 'steward'] as const;
export type Role = (typeof ROLES)[number];

export interface RoleGiven {
  user: string;
  role: Role;
}

/** The keys of the settings that carry a figure, each dated. */
export const DATED_KEYS = ['clerk-limit', 'floor', 'terms', 'terms-A', 'terms-B', 'terms-C', 'late-pct', 'cap', 'proof-threshold'] as const;
export type DatedKey = (typeof DATED_KEYS)[number];

/** The settings in force on the sheet's today; `dated` has, for each setting that carries a figure, every value
 * recorded for it by from-date. */
export interface Settings {
  /** The highest due a clerk may settle, with two decimals; null for no limit. */
  clerkLimit: string | null;
  /** The least a claim's live due comes to, with two decimals. */
  floor: string;
  /** The days after its date a claim is due by. */
  terms: number;
  /** Each grade's own terms in days; null when the grade uses the sheet's terms. */
  termsA: number | null;
  termsB: number | null;
  termsC: number | null;
  /** What an overdue claim gains of its figure for every full 30 days overdue, with two decimals. */
  latePct: string;
  /** The date (yyyy-mm-dd) a claim dated before rounds half up, on or after half to even; null for the
   * sheet's today. */
  policyDate: string | null;
  /** The date (yyyy-mm-dd) from which, while the sheet's today is on or after it, a claim opened is numbered
   * within its period; null when off. */
  periodNumberingFrom: string | null;
  /** The most a person's due-total comes to, with two decimals; null for none. */
  cap: string | null;
  /** The due above which a claim needs a proof before it is settled or approved, with two decimals; null for
   * none. */
  proofThreshold: string | null;
  dated: Record<DatedKey, Dated[]>;
}

// A sheet's settings: the role each user name has, its clerk limit, its floor, its terms and each grade's, its late percent, its policy date, its period numbering date, its cap and its proof threshold (those that carry a figure dated), read and changed through the
// server.
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  roles(sheetId: string): Observable<RoleGiven[]> {
    return this.http.get<RoleGiven[]>(`${this.url(sheetId)}/roles`, { headers: this.headers() });
  }

  setRole(sheetId: string, user: string, role: string): Observable<RoleGiven> {
    return this.http.put<RoleGiven>(`${this.url(sheetId)}/roles`, { user, role }, { headers: this.headers() });
  }

  get(sheetId: string): Observable<Settings> {
    return this.http.get<Settings>(`${this.url(sheetId)}/settings`, { headers: this.headers() });
  }

  /** Save the settings given; one left out is left as it is. A setting that carries a figure is recorded from
   * its from-date (`floorFrom` and so on), or from the sheet's today when none is given. */
  save(
    sheetId: string,
    settings: { clerkLimit?: string; floor?: string; terms?: string; termsA?: string; termsB?: string;
      termsC?: string; latePct?: string; policyDate?: string; periodNumberingFrom?: string; cap?: string;
      proofThreshold?: string; clerkLimitFrom?: string; floorFrom?: string; termsFrom?: string; termsAFrom?: string; termsBFrom?: string;
      termsCFrom?: string; latePctFrom?: string; capFrom?: string; proofThresholdFrom?: string; },
  ): Observable<Settings> {
    return this.http.put<Settings>(`${this.url(sheetId)}/settings`, settings, { headers: this.headers() });
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}
