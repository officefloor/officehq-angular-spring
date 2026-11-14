import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** The roles a user name can hold on a sheet; a name without one is a clerk. */
export const ROLES = ['clerk', 'steward'] as const;

/** A limit of 0.00 or more with up to two decimals. */
export const LIMIT_PATTERN = /^\d{1,13}(\.\d{1,2})?$/;

export interface Settings {
  /** Two decimals, as the server formats it; null when there is no limit. */
  clerkLimit: string | null;
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

export function settingsError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a clerk limit of 0.00 or more with up to two decimals, or leave it blank for no limit.';
  return 'Something went wrong; try again.';
}

export function roleError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a user name without spaces and a role of clerk or steward.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The role was changed at the same moment; try again.';
  return 'Something went wrong; try again.';
}
