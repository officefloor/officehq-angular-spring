import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** The roles a user name can hold on a sheet; a name without one is a clerk. */
export const ROLES = ['clerk', 'steward'] as const;

/** A limit of 0.00 or more with up to two decimals. */
export const LIMIT_PATTERN = /^\d{1,13}(\.\d{1,2})?$/;

/** A late percent of 0.00 or more with up to two decimals. */
export const LATE_PCT_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

/** Terms of a whole number of days. */
export const TERMS_PATTERN = /^\d{1,4}$/;

export interface Settings {
  /** Two decimals, as the server formats it; null when there is no limit. */
  clerkLimit: string | null;
  /** Two decimals, as the server formats it: a claim's live due is at least it. */
  floor: string;
  /** Days: a claim is due by its date plus them. */
  terms: number;
  /** Two decimals, as the server formats it: an overdue claim's due gains it for every full 30 days overdue. */
  latePct: string;
}

export interface RoleGrant {
  user: string;
  role: string;
}

// The sheet's settings and the roles user names hold on it, read and written through the server.
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  get(sheetId: string): Promise<Settings> {
    return firstValueFrom(this.http.get<Settings>(`${this.url(sheetId)}/settings`, { headers: this.headers() }));
  }

  setClerkLimit(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/clerk-limit`, { value }, { headers: this.headers() }),
    );
  }

  setFloor(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/floor`, { value }, { headers: this.headers() }),
    );
  }

  setTerms(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/terms`, { value }, { headers: this.headers() }),
    );
  }

  setLatePct(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/late-pct`, { value }, { headers: this.headers() }),
    );
  }

  roles(sheetId: string): Promise<RoleGrant[]> {
    return firstValueFrom(this.http.get<RoleGrant[]>(`${this.url(sheetId)}/roles`, { headers: this.headers() }));
  }

  setRole(sheetId: string, user: string, role: string): Promise<RoleGrant> {
    return firstValueFrom(this.http.put<RoleGrant>(`${this.url(sheetId)}/roles`, { user, role }, { headers: this.headers() }));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export const SETTINGS_INVALID =
  'Enter a clerk limit of 0.00 or more with up to two decimals (blank: no limit) and a floor of 0.00 or more with up to two decimals (blank: 1.00) and terms of a whole number of days (blank: 14) and a late percent of 0.00 or more with up to two decimals (blank: 0.00).';

export function settingsError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return SETTINGS_INVALID;
  return 'Something went wrong; try again.';
}

export function roleError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a user name without spaces and a role of clerk or steward.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The role was changed at the same moment; try again.';
  return 'Something went wrong; try again.';
}
